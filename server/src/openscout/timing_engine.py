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

import time

from .face_engine import MSFaceEngine, OpenFaceEngine
from .object_engine import OpenScoutObjectEngine


# TODO: these timing engines need work as the metrics here are still
# inherited from OpenRTiST
class TimingOpenFaceEngine(OpenFaceEngine):
    def __init__(self, args):
        super().__init__(args)
        self.count = 0
        self.lasttime = time.time()
        self.lastcount = 0
        self.lastprint = self.lasttime

    def handle(self, from_client):
        self.t0 = time.time()
        result = super().handle(from_client)
        self.t3 = time.time()

        self.count += 1
        if self.t3 - self.lastprint > 5:
            print(f"pre {(self.t1 - self.t0) * 1000:.1f} ms, ", end="")
            print(f"infer {(self.t2 - self.t1) * 1000:.1f} ms, ", end="")
            print(f"post {(self.t3 - self.t2) * 1000:.1f} ms, ", end="")
            print(f"wait {(self.t0 - self.lasttime) * 1000:.1f} ms, ", end="")
            print(f"fps {1.0 / (self.t3 - self.lasttime):.2f}")
            print(
                "avg fps: {:.2f}".format(
                    (self.count - self.lastcount) / (self.t3 - self.lastprint)
                )
            )
            print()
            self.lastcount = self.count
            self.lastprint = self.t3

        self.lasttime = self.t3

        return result

    def infer(self, image):
        self.t1 = time.time()
        results = super().infer(image)
        self.t2 = time.time()

        return results


class TimingMSFaceEngine(MSFaceEngine):
    def __init__(self, args):
        super().__init__(args)
        self.count = 0
        self.lasttime = time.time()
        self.lastcount = 0
        self.lastprint = self.lasttime

    def handle(self, from_client):
        self.t0 = time.time()
        result = super().handle(from_client)
        self.t3 = time.time()

        self.count += 1
        if self.t3 - self.lastprint > 5:
            print(f"pre {(self.t1 - self.t0) * 1000:.1f} ms, ", end="")
            print(f"infer {(self.t2 - self.t1) * 1000:.1f} ms, ", end="")
            print(f"post {(self.t3 - self.t2) * 1000:.1f} ms, ", end="")
            print(f"wait {(self.t0 - self.lasttime) * 1000:.1f} ms, ", end="")
            print(f"fps {1.0 / (self.t3 - self.lasttime):.2f}")
            print(
                "avg fps: {:.2f}".format(
                    (self.count - self.lastcount) / (self.t3 - self.lastprint)
                )
            )
            print()
            self.lastcount = self.count
            self.lastprint = self.t3

        self.lasttime = self.t3

        return result

    def detection(self, image):
        self.t1 = time.time()
        results = super().detection(image)
        self.t2 = time.time()

        return results

    def recognition(self, image):
        self.t2 = time.time()
        results = super().detection(image)
        self.t3 = time.time()

        return results


class TimingObjectEngine(OpenScoutObjectEngine):
    def __init__(self, args):
        super().__init__(args)
        self.count = 0
        self.lasttime = time.time()
        self.lastcount = 0
        self.lastprint = self.lasttime

    def handle(self, from_client):
        self.t0 = time.time()
        result = super().handle(from_client)
        self.t3 = time.time()

        self.count += 1
        if self.t3 - self.lastprint > 5:
            print(f"pre {(self.t1 - self.t0) * 1000:.1f} ms, ", end="")
            print(f"infer {(self.t2 - self.t1) * 1000:.1f} ms, ", end="")
            print(f"post {(self.t3 - self.t2) * 1000:.1f} ms, ", end="")
            print(f"wait {(self.t0 - self.lasttime) * 1000:.1f} ms, ", end="")
            print(f"fps {1.0 / (self.t3 - self.lasttime):.2f}")
            print(
                "avg fps: {:.2f}".format(
                    (self.count - self.lastcount) / (self.t3 - self.lastprint)
                )
            )
            print()
            self.lastcount = self.count
            self.lastprint = self.t3

        self.lasttime = self.t3

        return result

    def inference(self, preprocessed):
        self.t1 = time.time()
        results = super().inference(preprocessed)
        self.t2 = time.time()

        return results
