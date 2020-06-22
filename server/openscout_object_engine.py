# OpenScout
#   - Distrubted Automated Situational Awareness
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

import time
import os
import cv2
import numpy as np
import logging
from gabriel_server import cognitive_engine
from gabriel_protocol import gabriel_pb2
from openscout_protocol import openscout_pb2
from PIL import Image
import tensorflow as tf 
tf.compat.v1.enable_eager_execution() 
from object_detection.utils import ops as utils_ops
from object_detection.utils import label_map_util
from object_detection.utils import visualization_utils as vis_util

#PATCHES
# patch tf1 into `utils.ops`
utils_ops.tf = tf.compat.v2

# Patch the location of gfile
tf.gfile = tf.io.gfile

#tf.get_logger().warning('test')
# WARNING:tensorflow:test
tf.get_logger().setLevel('ERROR')

logger = logging.getLogger(__name__)


class TFPredictor():
    def __init__(self,model_path): 
        model_name = model_path+'/saved_model'

        label_map_file_path = model_path+'/label_map.pbtxt'

        self.detection_model = self.load_model(model_name)
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

    def inferFile(self, image):
        #image_np = np.array(Image.open(image_file_path))
        results = self.infer(image)
        res_boxes = results['detection_boxes']
        res_classes = results['detection_classes']
        res_scores = results['detection_scores']
        return res_classes,res_scores, res_boxes
    
    def getMaxScoreClasses(self, res_scores, res_classes, res_boxes, min_score_thresh):
        return_scores = []
        return_classes = []
        return_boxes = []
        for i in range(len(res_scores)): 
            if res_scores[i] > (min_score_thresh):
                return_scores.append(res_scores[i])
                return_classes.append(res_classes[i])
                return_boxes.append(res_boxes[i])
            else:
                break  
        return return_classes,return_scores, return_boxes

    def runPredictor(self, image, min_score_thresh):
        classes = None
        scores = None
        boxes = None
        res_classes, res_scores, res_boxes = self.inferFile(image) 
        classes, scores, boxes = self.getMaxScoreClasses(res_scores, res_classes, res_boxes, min_score_thresh)  
        return self.category_index, classes, scores, boxes


class OpenScoutObjectEngine(cognitive_engine.Engine):
    ENGINE_NAME = "openscout-object"

    def __init__(self, compression_params, args):
        self.compression_params = compression_params

        # TODO support server display
        self.detector = TFPredictor(args.model)
        self.threshold = args.threshold
        self.store_detections = args.store
        logger.info("TensorFlowPredictor initialized with the following model path: {}".format(args.model))
        logger.info("Confidence Threshold: {}".format(self.threshold))
        if self.store_detections:
            self.storage_path = os.getcwd()+"/images/"
            os.mkdir(self.storage_path)
            logger.info("Storing detection images at {}".format(self.storage_path))


    def handle(self, from_client):
        if from_client.payload_type != gabriel_pb2.PayloadType.IMAGE:
            return cognitive_engine.wrong_input_format_error(from_client.frame_id)

        engine_fields = cognitive_engine.unpack_engine_fields(
            openscout_pb2.EngineFields, from_client
        )

        classname_index, classes, scores, boxes, image_np = self.process_image(from_client.payload)

        if len(classes) > 0:
            result = gabriel_pb2.ResultWrapper.Result()
            result.payload_type = gabriel_pb2.PayloadType.TEXT
            result.engine_name = self.ENGINE_NAME
            result_wrapper = gabriel_pb2.ResultWrapper()
            result_wrapper.frame_id = from_client.frame_id
            result_wrapper.status = gabriel_pb2.ResultWrapper.Status.SUCCESS
            r = ""
            for i in range(0, len(classes)):
                logger.info("Detected : {} - Score: {:.3f}".format(classname_index[classes[i]]['name'],scores[i]))
                r += "Detected {} ({:.3f})\n".format(classname_index[classes[i]]['name'],scores[i]).encode(encoding="utf-8")    
            result.payload = r
            result_wrapper.results.append(result)

            engine_fields = openscout_pb2.EngineFields()
            result_wrapper.engine_fields.Pack(engine_fields)

            if self.store_detections:
                vis_util.visualize_boxes_and_labels_on_image_array(
                    image_np,
                    np.squeeze(self.detector.output_dict['detection_boxes']),
                    np.squeeze(self.detector.output_dict['detection_classes']),
                    np.squeeze(self.detector.output_dict['detection_scores']),
                    classname_index,
                    use_normalized_coordinates=True,
                    line_thickness=4)
                path = self.storage_path + str(time.time()) + ".png"
                logger.info("Stored image: {}".format(path))
                Image.fromarray(image_np).save(path)

        else:
            result_wrapper = gabriel_pb2.ResultWrapper()
            result_wrapper.frame_id = from_client.frame_id
            result_wrapper.status = gabriel_pb2.ResultWrapper.Status.SUCCESS
            result_wrapper.engine_fields.Pack(engine_fields)

        return result_wrapper

    def process_image(self, image):
        np_data = np.fromstring(image, dtype=np.uint8)
        img = cv2.imdecode(np_data, cv2.IMREAD_COLOR)
        img = cv2.cvtColor(img, cv2.COLOR_BGR2RGB)

        #preprocessed = self.adapter.preprocessing(img)
        classname_index, classes, scores, boxes = self.inference(img)
        #img_out = self.adapter.postprocessing(post_inference)
        return classname_index, classes, scores, boxes, img

    def inference(self, img):
        """Allow timing engine to override this"""
        return self.detector.runPredictor(img, self.threshold)

