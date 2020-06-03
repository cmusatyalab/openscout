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
        return output_dict

    def inferFile(self, image_file_path):     
        image_np = np.array(Image.open(image_file_path))
        results = self.infer(image_np)
        res_boxes = results['detection_boxes']
        res_classes = results['detection_classes']
        res_scores = results['detection_scores'] 
        return [res_classes,res_scores]
    
    def getMaxScoreClasses(self, res_scores, res_classes, min_score_thresh):
        return_scores = []; return_classes = []  
        for i in range(len(res_scores)): 
            if res_scores[i] > (min_score_thresh):
                return_scores.append(res_scores[i])
                return_classes.append(res_classes[i])   
            else:
                break  
        return [return_classes,return_scores]

    def runPredictor(self, image_path, min_score_thresh):  
        [res_classes,res_scores] = self.inferFile(image_path) 
        [classes,scores] = self.getMaxScoreClasses(res_scores, res_classes, min_score_thresh)  
        if len(scores) > 0: return [self.category_index,classes,scores]
        else: return None 


class OpenScoutEngine(cognitive_engine.Engine):
    ENGINE_NAME = "openscout"

    def __init__(self, compression_params):
        self.compression_params = compression_params
        self.adapter = adapter

        # TODO support server display

        logger.info("FINISHED INITIALISATION")

    def handle(self, from_client):
        if from_client.payload_type != gabriel_pb2.PayloadType.IMAGE:
            return cognitive_engine.wrong_input_format_error(from_client.frame_id)

        engine_fields = cognitive_engine.unpack_engine_fields(
            openscout_pb2.EngineFields, from_client
        )

        image = self.process_image(from_client.payload)

        _, jpeg_img = cv2.imencode(".jpg", image, self.compression_params)
        img_data = jpeg_img.tostring()

        result = gabriel_pb2.ResultWrapper.Result()
        result.payload_type = gabriel_pb2.PayloadType.TEXT
        result.engine_name = self.ENGINE_NAME
        result.payload = img_data

        engine_fields = openscout_pb2.EngineFields()

        result_wrapper = gabriel_pb2.ResultWrapper()
        result_wrapper.frame_id = from_client.frame_id
        result_wrapper.status = gabriel_pb2.ResultWrapper.Status.SUCCESS
        result_wrapper.results.append(result)
        result_wrapper.engine_fields.Pack(engine_fields)

        return result_wrapper

    def process_image(self, image):

        # Preprocessing steps used by both engines
        np_data = np.fromstring(image, dtype=np.uint8)
        img = cv2.imdecode(np_data, cv2.IMREAD_COLOR)
        img = cv2.cvtColor(img, cv2.COLOR_BGR2RGB)

        preprocessed = self.adapter.preprocessing(img)
        post_inference = self.inference(preprocessed)
        img_out = self.adapter.postprocessing(post_inference)
        return img_out

    def inference(self, preprocessed):
        """Allow timing engine to override this"""
        return self.adapter.inference(preprocessed)

        return img_out
