import cv2
import numpy as np
import zmq
import time
import random

current_loc = {'latitude': 40.4136589, 'longitude': -79.9495332, 'altitude': 10}

def send_array(socket, A, flags=0, copy=True, track=False):
    """send a numpy array with metadata"""
    global current_loc
    md = dict(
        dtype = str(A.dtype),
        shape = A.shape,
        location = current_loc
    )
    socket.send_json(md, flags|zmq.SNDMORE)
    return socket.send(A, flags, copy=copy, track=track)

img = cv2.imread('mill19intersection.png')
img2 = cv2.imread('mill19-2.png')

context = zmq.Context()

#  Socket to talk to server
print("Connecting to hello world server…")
socket = context.socket(zmq.REQ)
socket.connect("tcp://localhost:5555")

#  Do 10 requests, waiting each time for a response
for request in range(10):
    print(f"Sending request {request} …")
    send_array(socket, img if random.random() > 0.5 else img2)
    time.sleep(random.random())
    #  Get the reply.
    message = socket.recv()
    print(f"Received reply {request} [ {message} ]")