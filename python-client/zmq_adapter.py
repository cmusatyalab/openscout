import cv2
import numpy as np
from gabriel_protocol import gabriel_pb2
from gabriel_client.websocket_client import ProducerWrapper
import logging
import zmq
import openscout_pb2
import uuid

logger = logging.getLogger(__name__)

class ZmqAdapter:
    def __init__(self, preprocess, source_name, display_frames):
        '''
        preprocess should take one frame parameter
        produce_engine_fields takes no parameters
        consume_frame should take one frame parameter and one engine_fields
        parameter
        '''
        self.location = {}
        self._preprocess = preprocess
        self._source_name = source_name
        self.display_frames = display_frames
        self.context = zmq.Context()
        self.socket = self.context.socket(zmq.SUB)
        self.socket.connect('tcp://localhost:5555')
        self.socket.setsockopt(zmq.SUBSCRIBE, b'')
        logger.info(f"ZmqAdapter has subscribed to all topics on localhost...")

    def recv_array(self, flags=0, copy=True, track=False):
        """recv a numpy array"""
        md = self.socket.recv_json(flags=flags)
        msg = self.socket.recv(flags=flags, copy=copy, track=track)
        buf = memoryview(msg)
        A = np.frombuffer(buf, dtype=md['dtype'])
        self.location = md['location']
        logger.debug(md['location'])
        return A.reshape(md['shape'])

    def produce_extras(self):
        extras = openscout_pb2.Extras()
        extras.client_id = str(uuid.uuid4())
        extras.location.latitude = self.location['latitude']
        extras.location.longitude = self.location['longitude']
        return extras

    def get_producer_wrappers(self):
        async def producer():
            frame = self.recv_array()

            if frame is None:
                return None

            frame = self._preprocess(frame)
            if self.display_frames:
                cv2.imshow("Frames sent to ZmqAdapter", frame)
                cv2.waitKey(1)

            _, jpeg_frame = cv2.imencode('.jpg', frame)

            input_frame = gabriel_pb2.InputFrame()
            input_frame.payload_type = gabriel_pb2.PayloadType.IMAGE
            input_frame.payloads.append(jpeg_frame.tobytes())

            extras = self.produce_extras()
            if extras is not None:
                input_frame.extras.Pack(extras)

            return input_frame

        return [
            ProducerWrapper(producer=producer, source_name=self._source_name)
        ]

    def consumer(self, result_wrapper):
        if len(result_wrapper.results) != 1:
            logger.error('Got %d results from server',
                            len(result_wrapper.results))
            return

        result = result_wrapper.results[0]
        if result.payload_type != gabriel_pb2.PayloadType.TEXT:
            type_name = gabriel_pb2.PayloadType.Name(result.payload_type)
            logger.error('Got result of type %s', type_name)
            return
        print(result.payload.decode('utf-8'))