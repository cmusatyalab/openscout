#!/usr/bin/env python3

# Copyright 2021 Carnegie Mellon University
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#      http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

import cv2
import numpy as np
import zmq
import time
import random
import os

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



context = zmq.Context()

#  Socket to talk to server
print("Publishing images from current working directory on port 5555...")
socket = context.socket(zmq.PUB)
socket.bind('tcp://*:5555')

for entry in os.scandir("./"):
    if entry.is_file():
        if (entry.path.endswith(".jpg") or entry.path.endswith(".png")):
            img = cv2.imread(entry.path)
            print(f"Sending request with {entry.path}...")
            send_array(socket, img)
            time.sleep(random.random())