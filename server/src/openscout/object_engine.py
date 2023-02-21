# OpenScout
#   - Distributed Automated Situational Awareness
#
#   Author: Thomas Eiszler <teiszler@andrew.cmu.edu>
#
#   Copyright (C) 2020 Carnegie Mellon University
#   Licensed under the Apache License, Version 2.0 (the "License");
#   you may not use this file except in compliance with the License.
#   You may obtain a copy of the License at
#
#       http://www.apache.org/licenses/LICENSE-2.0
#
#   Unless required by applicable law or agreed to in writing, software
#   distributed under the License is distributed on an "AS IS" BASIS,
#   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
#   See the License for the specific language governing permissions and
#   limitations under the License.
#
#

import logging
import os
import time

import cv2
import importlib_resources
import numpy as np
import torch
from gabriel_protocol import gabriel_pb2
from gabriel_server import cognitive_engine
from PIL import Image, ImageDraw

from .protocol import openscout_pb2

logger = logging.getLogger(__name__)
logger.setLevel(logging.DEBUG)

detection_log = logging.getLogger("object-engine")
fh = logging.FileHandler("/openscout-server/openscout-object-engine.log")
fh.setLevel(logging.INFO)
formatter = logging.Formatter("%(message)s")
fh.setFormatter(formatter)
detection_log.addHandler(fh)


class PytorchPredictor:
    def __init__(self, model, threshold):
        path_prefix = "./model/"
        model_path = path_prefix + model + ".pt"
        logger.info(f"Loading new model {model} at {model_path}...")
        self.detection_model = self.load_model(model_path)
        self.detection_model.conf = threshold
        self.output_dict = None

    def load_model(self, model_path):
        # Load model
        model = torch.hub.load("ultralytics/yolov5", "custom", path=model_path)
        return model

    def infer(self, image):
        return self.model(image)


class OpenScoutObjectEngine(cognitive_engine.Engine):
    ENGINE_NAME = "openscout-object"

    def __init__(self, args):
        self.detector = PytorchPredictor(args.model, args.threshold)
        self.threshold = args.threshold
        self.store_detections = args.store
        self.model = args.model

        if args.exclude:
            self.exclusions = list(
                map(int, args.exclude.split(","))
            )  # split string to int list
            logger.info(f"Excluding the following class ids: {self.exclusions}")
        else:
            self.exclusions = None

        logger.info(
            f"Predictor initialized with the following model path: {args.model}"
        )
        logger.info(f"Confidence Threshold: {self.threshold}")

        if self.store_detections:
            watermark_path = importlib_resources.files("openscout").joinpath(
                "watermark.png"
            )
            self.watermark = Image.open(watermark_path)
            self.storage_path = os.getcwd() + "/images/"
            try:
                os.mkdir(self.storage_path)
            except FileExistsError:
                logger.info("Images directory already exists.")
            logger.info(f"Storing detection images at {self.storage_path}")

    def handle(self, input_frame):
        if input_frame.payload_type == gabriel_pb2.PayloadType.TEXT:
            # if the payload is TEXT, say from a CNC client, we ignore
            status = gabriel_pb2.ResultWrapper.Status.SUCCESS
            result_wrapper = cognitive_engine.create_result_wrapper(status)
            result_wrapper.result_producer_name.value = self.ENGINE_NAME
            result = gabriel_pb2.ResultWrapper.Result()
            result.payload_type = gabriel_pb2.PayloadType.TEXT
            result.payload = "Ignoring TEXT payload.".encode(encoding="utf-8")
            result_wrapper.results.append(result)
            return result_wrapper

        extras = cognitive_engine.unpack_extras(openscout_pb2.Extras, input_frame)

        if extras.model != "" and extras.model != self.model:
            if not os.path.exists("./model/" + extras.model):
                logger.error(
                    f"Model named {extras.model} not found. "
                    "Sticking with previous model."
                )
            else:
                self.detector = PytorchPredictor(extras.detection_model, self.threshold)
                self.model = extras.detection_model
        self.t0 = time.time()
        results, image_np = self.process_image(input_frame.payloads[0])
        timestamp_millis = int(time.time() * 1000)
        status = gabriel_pb2.ResultWrapper.Status.SUCCESS
        result_wrapper = cognitive_engine.create_result_wrapper(status)
        result_wrapper.result_producer_name.value = self.ENGINE_NAME

        filename = str(timestamp_millis) + ".jpg"
        img = Image.fromarray(image_np)
        path = self.storage_path + "/received/" + filename
        img.save(path, format="JPEG")

        if len(results.pred) > 0:
            df = results.pandas().xyxy[0]  # pandas dataframe
            logger.debug(df)
            # convert dataframe to python lists
            classes = df["class"].values.tolist()
            scores = df["confidence"].values.tolist()
            names = df["name"].values.tolist()

            result = gabriel_pb2.ResultWrapper.Result()
            result.payload_type = gabriel_pb2.PayloadType.TEXT

            detections_above_threshold = False
            r = []
            for i in range(0, len(classes)):
                if scores[i] > self.threshold:
                    if self.exclusions is None or classes[i] not in self.exclusions:
                        detections_above_threshold = True
                        logger.info(f"Detected : {names[i]} - Score: {scores[i]:.3f}")
                        if i > 0:
                            r += ", "
                        r += f"Detected {names[i]} ({scores[i]:.3f})"
                        if self.store_detections:
                            detection_log.info(
                                "{},{},{},{},{},{:.3f},{}".format(
                                    timestamp_millis,
                                    extras.drone_id,
                                    extras.location.latitude,
                                    extras.location.longitude,
                                    names[i],
                                    scores[i],
                                    os.environ["WEBSERVER"] + "/detected/" + filename,
                                )
                            )
                        else:
                            detection_log.info(
                                "{},{},{},{},{},{:.3f},".format(
                                    timestamp_millis,
                                    extras.drone_id,
                                    extras.location.latitude,
                                    extras.location.longitude,
                                    names[i],
                                    scores[i],
                                )
                            )

            if detections_above_threshold:
                result.payload = r.encode(encoding="utf-8")
                result_wrapper.results.append(result)

                if self.store_detections:
                    try:
                        # results._run(
                        #     save=True,
                        #     labels=True,
                        #     save_dir=Path("openscout-vol/")
                        # )
                        results.render()
                        img = Image.fromarray(results.ims[0])
                        draw = ImageDraw.Draw(img)
                        draw.bitmap((0, 0), self.watermark, fill=None)
                        path = self.storage_path + filename
                        img.save(path, format="JPEG")
                        logger.info(f"Stored image: {path}")
                    except IndexError:
                        logger.exception("IndexError while getting bounding boxes")
                        return result_wrapper

        return result_wrapper

    def process_image(self, image):
        np_data = np.fromstring(image, dtype=np.uint8)
        img = cv2.imdecode(np_data, cv2.IMREAD_COLOR)
        img = cv2.cvtColor(img, cv2.COLOR_BGR2RGB)

        output_dict = self.inference(img)
        return output_dict, img

    def inference(self, img):
        """Allow timing engine to override this"""
        return self.detector.detection_model(img)
