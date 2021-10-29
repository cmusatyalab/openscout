import argparse
import cv2
from gabriel_client.websocket_client import WebsocketClient
from gabriel_client.opencv_adapter import OpencvAdapter
from gabriel_protocol import gabriel_pb2
import logging
import zmq
from zmq_adapter import ZmqAdapter

WEBSOCKET_PORT = 9099
DEFAULT_SOURCE_NAME = 'openscout'

logger = logging.getLogger(__name__)

def configure_logging():
    logging.basicConfig(format="%(levelname)s: %(message)s", level=logging.INFO)

def preprocess(frame):
    return frame

def produce_extras():
    pass

def local_consumer(result_wrapper):
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

def consume_frame(frame, _):
    cv2.imshow('Image from server', frame)
    cv2.waitKey(1)


def main():
    configure_logging()
    parser = argparse.ArgumentParser()
    parser.add_argument('-s', '--server', default='openscout-demo.cmusatyalab.org',
        help='Specify address of OpenScout server [default: openscout-demo.cmusatyalab.org')
    parser.add_argument('-p', '--port', default='9099', help='Specify websocket port [default: 9099]')
    parser.add_argument('-c', '--camera', action='store_true', help='Use cv2.VideoCapture(0) adapter instead of ZmqAdapter')
    parser.add_argument('-d', '--display', action='store_true', help='Optionally display the frames received by the ZmqAdapter using cv2.imshow')
    
    args = parser.parse_args()

    if args.camera:
        capture = cv2.VideoCapture(0)
        adapter = OpencvAdapter(
            preprocess, produce_extras, consume_frame, capture, DEFAULT_SOURCE_NAME)
    else:
        adapter = ZmqAdapter(preprocess, DEFAULT_SOURCE_NAME, args.display)

    client = WebsocketClient(
        args.server, args.port,
        adapter.get_producer_wrappers(), local_consumer if args.camera else adapter.consumer
    )
    client.launch()


if __name__ == '__main__':
    main()