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

import base64
import time
import os
import cv2
import numpy as np
import logging
from gabriel_server import cognitive_engine
from gabriel_protocol import gabriel_pb2
from cnc_protocol import cnc_pb2
from io import BytesIO
from PIL import Image, ImageDraw
import tensorflow as tf 
tf.compat.v1.enable_eager_execution() 
from object_detection.utils import ops as utils_ops
from object_detection.utils import label_map_util
from object_detection.utils import visualization_utils as vis_util
import traceback
import json
from scipy.spatial.transform import Rotation as R

#PATCHES
# patch tf1 into `utils.ops`
utils_ops.tf = tf.compat.v2

# Patch the location of gfile
tf.gfile = tf.io.gfile

#tf.get_logger().warning('test')
# WARNING:tensorflow:test
tf.get_logger().setLevel('ERROR')

logger = logging.getLogger(__name__)
logger.setLevel(logging.DEBUG)

detection_log = logging.getLogger("object-engine")
fh = logging.FileHandler('/openscout/server/openscout-object-engine.log')
fh.setLevel(logging.INFO)
formatter = logging.Formatter('%(message)s')
fh.setFormatter(formatter)
detection_log.addHandler(fh)

class TFPredictor():
    def __init__(self,model):
        path_prefix = './model/'
        model_path = path_prefix+ model +'/saved_model'
        label_map_file_path = path_prefix + model +'/label_map.pbtxt'
        logger.info(f"Loading new model {model} at {model_path}...")
        self.detection_model = self.load_model(model_path)
        self.category_index = label_map_util.create_category_index_from_labelmap(label_map_file_path, use_display_name=True) 
        self.output_dict = None

    def load_model(self,model_dir):   
        model = tf.compat.v2.saved_model.load(export_dir=str(model_dir), tags=None)
        model = model.signatures['serving_default'] 
        return model 
    
    def infer(self, image):  
        image = np.asarray(image)  
        # The input needs to be a tensor, convert it using `tf.convert_to_tensor`.
        input_tensor = tf.convert_to_tensor(image)
        # The model expects a batch of images, so add an axis with `tf.newaxis`.
        input_tensor = input_tensor[tf.newaxis,...]

        # Run inference
        output_dict = self.detection_model(input_tensor)
        num_detections = int(output_dict.pop('num_detections'))
        output_dict = {key:value[0, :num_detections].numpy() 
                         for key,value in output_dict.items()}
        output_dict['num_detections'] = num_detections

          # detection_classes should be ints.
        output_dict['detection_classes'] = output_dict['detection_classes'].astype(np.int64)

          # Handle models with masks:
        if 'detection_masks' in output_dict:
          # Reframe the the bbox mask to the image size.
          detection_masks_reframed = utils_ops.reframe_box_masks_to_image_masks(
                    output_dict['detection_masks'], output_dict['detection_boxes'],
                     image.shape[0], image.shape[1])
          detection_masks_reframed = tf.cast(detection_masks_reframed > 0.5,
                                               tf.uint8)
          output_dict['detection_masks_reframed'] = detection_masks_reframed.numpy()

        self.output_dict = output_dict
        return output_dict

class OpenScoutObjectEngine(cognitive_engine.Engine):
    ENGINE_NAME = "openscout-object"

    def __init__(self, args):
        self.detector = TFPredictor(args.model)
        self.threshold = args.threshold
        self.store_detections = args.store
        self.model = args.model
        self.drone_type = args.drone

        if args.exclude:
            self.exclusions = list(map(int, args.exclude.split(","))) #split string to int list
            logger.info("Excluding the following class ids: {}".format(self.exclusions))
        else:
            self.exclusions = None

        logger.info("TensorFlowPredictor initialized with the following model path: {}".format(args.model))
        logger.info("Confidence Threshold: {}".format(self.threshold))

        if self.store_detections:
            self.watermark = Image.open(os.getcwd()+"/watermark.png")
            self.storage_path = os.getcwd()+"/images/"
            try:
                os.mkdir(self.storage_path)
            except FileExistsError:
                logger.info("Images directory already exists.")
            logger.info("Storing detection images at {}".format(self.storage_path))

    def find_intersection(self, target_dir, target_insct):
        plane_pt = np.array([0, 0, 0])
        plane_norm = np.array([0, 0, 1])

        if (plane_norm.dot(target_dir).all() == 0):
            return None

        t = (plane_norm.dot(plane_pt) - plane_norm.dot(target_insct)) / plane_norm.dot(target_dir)
        return target_insct + (t * target_dir)

    def estimateGPS(self, drone_lat, drone_lon, camera_pitch, drone_yaw, drone_elev, target_x_pix, target_y_pix):
        # Establish parameters.
        EARTH_RADIUS = 6378137.0
        north_vec = np.array([0, 1, 0]) # Create a vector pointing due north.
        if self.drone_type is 'anafi':
            HFOV = 69 # Horizontal FOV An.
            VFOV = 43 # Vertical FOV.
        elif self.dron_type is  'usa':
            HFOV = 69 # Horizontal FOV An.
            VFOV = 43 # Vertical FOV.
        else:
            raise "Unsupported drone type!"
        pixel_center = (640/2, 480/2) # Center pixel location of the camera.

        # Perform rotations.
        logger.info("Pitch: {0}, Yaw: {1}, Elev: {2}".format(camera_pitch, drone_yaw, drone_elev))

        r = R.from_euler('ZYX', [drone_yaw, 0, camera_pitch], degrees=True) # Rotate the due north vector to camera center.
        camera_center = r.as_matrix().dot(north_vec)

        logger.info("Camera centered at: ({0}, {1}, {2})".format(camera_center[0], camera_center[1], camera_center[2]))

        target_yaw_angle = ((target_x_pix - pixel_center[0]) / pixel_center[0]) * (HFOV / 2)
        target_pitch_angle = ((target_y_pix - pixel_center[1]) / pixel_center[1]) * (VFOV / 2)
        r = R.from_euler('ZYX', [drone_yaw + target_yaw_angle, 0, camera_pitch + target_pitch_angle], degrees=True) # Rotate the camera center vector to target center.
        target_dir = r.as_matrix().dot(north_vec)
        logger.info("Target yaw: {0}, Target pitch: {1}".format(target_yaw_angle, target_pitch_angle))
        logger.info("Target centered at: ({0}, {1}, {2})".format(target_dir[0], target_dir[1], target_dir[2]))

        # Finding the intersection with the plane.
        insct = self.find_intersection(target_dir, np.array([0, 0, drone_elev]))
        logger.info("Intersection with ground plane: ({0}, {1}, {2})".format(insct[0], insct[1], insct[2]))

        est_lat = drone_lat + (180 / np.pi) * (insct[1] / EARTH_RADIUS)
        est_lon = drone_lon + (180 / np.pi) * (insct[0] / EARTH_RADIUS) / np.cos(drone_lat)
        logger.info("Estimated GPS location: ({0}, {1})".format(est_lat, est_lon))
        return est_lat, est_lon

    def handle(self, input_frame):
        if input_frame.payload_type == gabriel_pb2.PayloadType.TEXT:
            #if the payload is TEXT, say from a CNC client, we ignore
            status = gabriel_pb2.ResultWrapper.Status.SUCCESS
            result_wrapper = cognitive_engine.create_result_wrapper(status)
            result_wrapper.result_producer_name.value = self.ENGINE_NAME
            result = gabriel_pb2.ResultWrapper.Result()
            result.payload_type = gabriel_pb2.PayloadType.TEXT
            result.payload = f'Ignoring TEXT payload.'.encode(encoding="utf-8")
            result_wrapper.results.append(result)
            return result_wrapper

        extras = cognitive_engine.unpack_extras(cnc_pb2.Extras, input_frame)

        if extras.detection_model != '' and extras.detection_model != self.model:
            if not os.path.exists('./model/'+ extras.detection_model):
                logger.error(f"Model named {extras.detection_model} not found. Sticking with previous model.")
            else:
                self.detector = TFPredictor(extras.detection_model)
                self.model = extras.detection_model

        output_dict, image_np = self.process_image(input_frame.payloads[0])

        status = gabriel_pb2.ResultWrapper.Status.SUCCESS
        result_wrapper = cognitive_engine.create_result_wrapper(status)
        result_wrapper.result_producer_name.value = self.ENGINE_NAME

        if output_dict['num_detections'] > 0:
            #convert numpy arrays to python lists
            classes = output_dict['detection_classes'].tolist()
            boxes = output_dict['detection_boxes'].tolist()
            scores = output_dict['detection_scores'].tolist()

            result = gabriel_pb2.ResultWrapper.Result()
            result.payload_type = gabriel_pb2.PayloadType.TEXT

            detections_above_threshold = False
            filename = str(time.time()) + ".jpg"
            r = []
            for i in range(0, len(classes)):
                if self.exclusions is None or classes[i] not in self.exclusions:
                    if(scores[i] > self.threshold):
                        detections_above_threshold = True
                        logger.info("Detected : {} - Score: {:.3f}".format(self.detector.category_index[classes[i]]['name'],scores[i]))
                        #[y_min, x_min, y_max, x_max]
                        box = boxes[i]
                        target_x_pix = int(((box[3] - box[1]) / 2.0) + box[1]) * image_np.shape[1]
                        target_y_pix = int(((box[2] - box[0]) / 2.0) + box[0]) * image_np.shape[0]
                        lat, lon = self.estimateGPS(extras.location.latitude, extras.location.longitude, extras.status.gimbal_pitch, extras.status.bearing*(180 /np.pi), extras.location.altitude, target_x_pix, target_y_pix )
                        r.append({'id': i, 'class': self.detector.category_index[classes[i]]['name'], 'score': scores[i], 'lat': lat, 'lon': lon, 'box': box})
                        if self.store_detections:
                            detection_log.info("{},{},{},{},{:.3f},{}".format(extras.drone_id, lat, lon, self.detector.category_index[classes[i]]['name'],scores[i], os.environ["WEBSERVER"]+"/"+filename))
                        else:
                            detection_log.info("{},{},{},{},{:.3f},".format(extras.drone_id, lat, lon, self.detector.category_index[classes[i]]['name'], scores[i]))

            if detections_above_threshold:
                logger.info(json.dumps(r,sort_keys=True, indent=4))
                result.payload = json.dumps(r).encode(encoding="utf-8")
                result_wrapper.results.append(result)

                if self.store_detections:
                    try:
                        boxes = output_dict['detection_boxes']
                        classes = output_dict['detection_classes']
                        scores = output_dict['detection_scores']
                        vis_util.visualize_boxes_and_labels_on_image_array(
                            image_np,
                            boxes,
                            classes,
                            scores,
                            self.detector.category_index,
                            use_normalized_coordinates=True,
                            min_score_thresh=self.threshold,
                            line_thickness=4)

                        img = Image.fromarray(image_np)
                        draw = ImageDraw.Draw(img)
                        draw.bitmap((0,0), self.watermark, fill=None)
                        path = self.storage_path + filename
                        img.save(path, format="JPEG")
                        logger.info("Stored image: {}".format(path))
                    except IndexError as e:
                        logger.error(f"IndexError while getting bounding boxes [{traceback.format_exc()}]")
                        logger.error(f"{boxes} {classes} {scores}")
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
        return self.detector.infer(img)

