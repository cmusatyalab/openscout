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

import json
import logging
import os
import time
from io import BytesIO

import importlib_resources
import requests
from gabriel_protocol import gabriel_pb2
from gabriel_server import cognitive_engine
from PIL import Image, ImageDraw

from .protocol import openscout_pb2

logger = logging.getLogger(__name__)
logger.setLevel(logging.DEBUG)

detection_log = logging.getLogger("openface-engine")
fh = logging.FileHandler("/openscout-server/openscout-openface-engine.log")
fh.setLevel(logging.INFO)
formatter = logging.Formatter("%(message)s")
fh.setFormatter(formatter)
detection_log.addHandler(fh)


class OpenFaceEngine(cognitive_engine.Engine):
    ENGINE_NAME = "openscout-face"

    def __init__(self, args):
        self.new_faces = False
        self.endpoint = args.endpoint
        self.threshold = args.threshold
        self.store_detections = args.store

        logger.info(f"OpenFace server: {args.endpoint}")
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
        self.train()

    def train(self):
        response = requests.get("{}/{}".format(self.endpoint, "train")).json()
        logger.info(response)

    def infer(self, img):
        headers = {"content-type": "image/jpeg"}
        # send http request with image and receive response
        response = requests.post(
            "{}/{}".format(self.endpoint, "infer"), data=img, headers=headers
        )
        return response.text

    def getRectangle(self, person):
        return (
            (person["bb-tl-x"], person["bb-tl-y"]),
            (person["bb-br-x"], person["bb-br-y"]),
        )

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

        image = self.preprocess_image(input_frame.payloads[0])

        status = gabriel_pb2.ResultWrapper.Status.SUCCESS
        result_wrapper = cognitive_engine.create_result_wrapper(status)
        result_wrapper.result_producer_name.value = self.ENGINE_NAME

        if extras.is_training:
            training_dir = os.getcwd() + "/training/" + extras.name + "/"
            try:
                os.mkdir(training_dir)
            except FileExistsError:
                logger.info("Directory already exists.")
            img = Image.open(image)
            path = training_dir + str(time.time()) + ".png"
            logger.info(f"Stored training image: {path}")
            img.save(path)
            self.new_faces = True
        else:
            # if we received new images for training and training has ended...
            if self.new_faces:
                self.train()
                self.new_faces = False
                result = gabriel_pb2.ResultWrapper.Result()
                result.payload_type = gabriel_pb2.PayloadType.TEXT
                result.payload = "Retraining complete.".encode(encoding="utf-8")
                result_wrapper.results.append(result)
            else:
                faces_recognized = False
                response = self.infer(image)
                if response is not None:
                    identities = json.loads(response)
                    if len(identities) > 0:
                        logger.debug(
                            "Detected {} faces. Attempting recognition...".format(
                                len(identities)
                            )
                        )
                        # Identify faces
                        for person in identities:
                            logger.debug(person)
                            if person["confidence"] > self.threshold:
                                faces_recognized = True
                                logger.info(
                                    "Recognized: {} - Score: {:.3f}".format(
                                        person["name"], person["confidence"]
                                    )
                                )
                                result = gabriel_pb2.ResultWrapper.Result()
                                result.payload_type = gabriel_pb2.PayloadType.TEXT
                                result.payload = "Recognized {} ({:.3f})".format(
                                    person["name"], person["confidence"]
                                ).encode(encoding="utf-8")
                                result_wrapper.results.append(result)
                            else:
                                logger.debug("Confidence did not exceed threshold.")
                        if faces_recognized:
                            if self.store_detections:
                                bb_img = Image.open(image)

                                draw = ImageDraw.Draw(bb_img)
                                for person in identities:
                                    draw.rectangle(
                                        self.getRectangle(person),
                                        width=4,
                                        outline="red",
                                    )
                                    text = "{} ({:.3f})".format(
                                        person["name"], person["confidence"]
                                    )
                                    w, h = draw.textsize(text)
                                    xy = (
                                        (self.getRectangle(person)[0]),
                                        (
                                            self.getRectangle(person)[0][0] + w,
                                            self.getRectangle(person)[0][1] + h,
                                        ),
                                    )
                                    draw.rectangle(
                                        xy, width=4, outline="red", fill="red"
                                    )
                                    draw.text(
                                        self.getRectangle(person)[0], text, fill="black"
                                    )

                                draw.bitmap((0, 0), self.watermark, fill=None)
                                filename = str(time.time()) + ".png"
                                path = self.storage_path + filename
                                logger.info(f"Stored image: {path}")
                                bb_img.save(path)
                                bio = BytesIO()
                                bb_img.save(bio, format="JPEG")

                                detection_log.info(
                                    "{},{},{},{},{:.3f},{}".format(
                                        extras.client_id,
                                        extras.location.latitude,
                                        extras.location.longitude,
                                        person["name"],
                                        person["confidence"],
                                        os.environ["WEBSERVER"] + "/" + filename,
                                    )
                                )
                            else:
                                detection_log.info(
                                    "{},{},{},{},{:.3f},".format(
                                        extras.client_id,
                                        extras.location.latitude,
                                        extras.location.longitude,
                                        person["name"],
                                        person["confidence"],
                                    )
                                )
                        else:
                            logger.debug(
                                "No faces recognized with confidence above {}.".format(
                                    self.threshold
                                )
                            )
                    else:
                        result = gabriel_pb2.ResultWrapper.Result()
                        result.payload_type = gabriel_pb2.PayloadType.TEXT
                        result.payload = "No faces detected.".encode(encoding="utf-8")
        return result_wrapper

    def preprocess_image(self, image):
        return BytesIO(image)
