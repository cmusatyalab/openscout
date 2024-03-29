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
from io import BytesIO

import importlib_resources
from azure.cognitiveservices.vision.face import FaceClient
from azure.cognitiveservices.vision.face.models import (
    APIErrorException,
    TrainingStatusType,
)
from gabriel_protocol import gabriel_pb2
from gabriel_server import cognitive_engine
from msrest.authentication import CognitiveServicesCredentials
from msrest.exceptions import ValidationError
from PIL import Image, ImageDraw

from .protocol import openscout_pb2

logger = logging.getLogger(__name__)
logger.setLevel(logging.DEBUG)

detection_log = logging.getLogger("msface-engine")
fh = logging.FileHandler("/openscout-server/openscout-msface-engine.log")
fh.setLevel(logging.INFO)
formatter = logging.Formatter("%(message)s")
fh.setFormatter(formatter)
detection_log.addHandler(fh)


class MSFaceEngine(cognitive_engine.Engine):
    ENGINE_NAME = "openscout-face"
    PERSON_GROUP_ID = "known-persons"

    def __init__(self, args):
        self.new_faces = False
        self.apikey = args.apikey
        self.endpoint = args.endpoint
        self.threshold = args.threshold
        self.store_detections = args.store
        # Create an authenticated FaceClient.
        self.face_client = FaceClient(
            args.endpoint, CognitiveServicesCredentials(args.apikey)
        )
        try:
            self.face_client.person_group.create(
                person_group_id=self.PERSON_GROUP_ID, name=self.PERSON_GROUP_ID
            )
        except APIErrorException:
            logger.error("Person group already exists.")
        # train statically for now
        self.train()

        logger.info(f"Cognitive server endpoint: {args.endpoint}")
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

    def train(self):
        training_dir = os.getcwd() + "/training/"
        for root, dirs, files in os.walk(training_dir):
            if root == training_dir:
                continue
            # check if person already exists in group, if not create it
            name = os.path.split(root)[1]
            create = True
            person = None
            try:
                for p in self.face_client.person_group_person.list(
                    self.PERSON_GROUP_ID
                ):
                    if p.name == name:
                        create = False
                        person = p
                        logger.error("Person already exists in group.")
                        break
            except APIErrorException as a:
                logger.error(a.message)

            if create:
                person = self.face_client.person_group_person.create(
                    self.PERSON_GROUP_ID, name
                )
            for filename in files:
                filepath = root + os.sep + filename
                logger.info(f"Adding training image {filename} to person {name}")

                if filepath.endswith(".jpg") or filepath.endswith(".png"):
                    w = open(filepath, "r+b")
                    try:
                        self.face_client.person_group_person.add_face_from_stream(
                            self.PERSON_GROUP_ID, person.person_id, w
                        )
                    except APIErrorException as a:
                        logger.error(a.message)

        self.face_client.person_group.train(self.PERSON_GROUP_ID)

        while True:
            training_status = self.face_client.person_group.get_training_status(
                self.PERSON_GROUP_ID
            )
            logger.info(f"Training status: {training_status.status}.")
            if training_status.status is TrainingStatusType.succeeded:
                break
            elif training_status.status is TrainingStatusType.failed:
                logger.info("Training the person group has failed.")
            time.sleep(1)

    def detection(self, image):
        """Allow timing engine to override this"""
        # Detect a face in an image that contains a single face
        detected_faces = self.face_client.face.detect_with_stream(image=image)
        if not detected_faces:
            logger.debug("No face detected from image.")

        return detected_faces

    def recognition(self, face_ids):
        """Allow timing engine to override this"""
        return self.face_client.face.identify(face_ids, self.PERSON_GROUP_ID)

    # Convert width height to a point in a rectangle
    def getRectangle(self, faceDictionary):
        rect = faceDictionary.face_rectangle
        left = rect.left
        top = rect.top
        right = left + rect.width
        bottom = top + rect.height

        return ((left, top), (right, bottom))

    def handle(self, input_frame):
        if input_frame.payload_type == gabriel_pb2.PayloadType.TEXT:
            # if the payload is TEXT, say from a CNC client, we ignore
            status = gabriel_pb2.ResultWrapper.Status.SUCCESS
            result_wrapper = cognitive_engine.create_result_wrapper(status)
            result_wrapper.result_producer_name.value = self.ENGINE_NAME
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
                detections = self.detection(image)

                face_ids = []
                for face in detections:
                    face_ids.append(face.face_id)

                if len(face_ids) > 0:
                    logger.debug(
                        "Detected {} faces. Attempting recognition...".format(
                            len(face_ids)
                        )
                    )
                    # Identify faces
                    try:
                        identities = self.recognition(face_ids)
                    except ValidationError as v:
                        logger.error(v.message)

                    for person in identities:
                        logger.debug(person)
                        if len(person.candidates) > 0:
                            if person.candidates[0].confidence > self.threshold:
                                match = self.face_client.person_group_person.get(
                                    self.PERSON_GROUP_ID, person.candidates[0].person_id
                                )
                                logger.info(
                                    "Recognized: {} - Score: {:.3f}".format(
                                        match.name, person.candidates[0].confidence
                                    )
                                )  # Get topmost confidence score
                                result = gabriel_pb2.ResultWrapper.Result()
                                result.payload_type = gabriel_pb2.PayloadType.TEXT
                                result.payload = "Recognized {} ({:.3f})".format(
                                    match.name, person.candidates[0].confidence
                                ).encode(encoding="utf-8")
                                result_wrapper.results.append(result)

                                if self.store_detections:
                                    bb_img = Image.open(image)
                                    draw = ImageDraw.Draw(bb_img)
                                    for face in detections:
                                        draw.rectangle(
                                            self.getRectangle(face),
                                            width=4,
                                            outline="red",
                                        )
                                        text = "{} ({:.3f})".format(
                                            match.name, person.candidates[0].confidence
                                        )
                                        w, h = draw.textsize(text)
                                        xy = (
                                            (self.getRectangle(face)[0]),
                                            (
                                                self.getRectangle(face)[0][0] + w,
                                                self.getRectangle(face)[0][1] + h,
                                            ),
                                        )
                                        draw.rectangle(
                                            xy, width=4, outline="red", fill="red"
                                        )
                                        draw.text(
                                            self.getRectangle(face)[0],
                                            text,
                                            fill="black",
                                        )

                                    draw.bitmap((0, 0), self.watermark, fill=None)
                                    filename = str(time.time()) + ".png"
                                    path = self.storage_path + filename
                                    logger.info(f"Stored image: {path}")
                                    bb_img.save(path)
                                    detection_log.info(
                                        "{},{},{},{},{:.3f},{}".format(
                                            extras.client_id,
                                            extras.location.latitude,
                                            extras.location.longitude,
                                            match.name,
                                            person.candidates[0].confidence,
                                            os.environ["WEBSERVER"] + "/" + filename,
                                        )
                                    )
                                else:
                                    detection_log.info(
                                        "{},{},{},{},{:.3f}".format(
                                            extras.client_id,
                                            extras.location.latitude,
                                            extras.location.longitude,
                                            match.name,
                                            person.candidates[0].confidence,
                                        )
                                    )
                            else:
                                logger.debug("Confidence did not exceed threshold.")
                        else:
                            logger.debug("No faces recognized from person group.")
        return result_wrapper

    def preprocess_image(self, image):
        return BytesIO(image)
