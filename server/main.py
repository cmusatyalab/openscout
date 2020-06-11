#!/usr/bin/env python3
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
from gabriel_server.local_engine import runner
from openscout_engine import OpenScoutEngine
from timing_engine import TimingEngine
import logging
import cv2
import argparse
import importlib

DEFAULT_PORT = 9099
DEFAULT_NUM_TOKENS = 2
INPUT_QUEUE_MAXSIZE = 60
COMPRESSION_PARAMS = [cv2.IMWRITE_JPEG_QUALITY, 67]

logging.basicConfig(level=logging.INFO)

logger = logging.getLogger(__name__)


def main():
    parser = argparse.ArgumentParser(
        formatter_class=argparse.ArgumentDefaultsHelpFormatter
    )
    parser.add_argument(
        "-t", "--tokens", type=int, default=DEFAULT_NUM_TOKENS, help="number of tokens"
    )
    parser.add_argument(
        "-c",
        "--cpu-only",
        action="store_true",
        help="Pass this flag to prevent the GPU from being used.",
    )
    parser.add_argument(
        "--timing", action="store_true", help="Print timing information"
    )
    parser.add_argument(
        "-p", "--port", type=int, default=DEFAULT_PORT, help="Set port number"
    )

    parser.add_argument(
        "-m", "--model", default="./model/tank_uni", help="Path to directory containing TPOD model"
    )

    parser.add_argument(
        "-r", "--threshold", type=float, default=0.85, help="Confidence threshold"
    )

    parser.add_argument(
        "-s", "--store", action="store_true", default=True, help="Store images with detections"
    )

    args = parser.parse_args()

    def engine_setup():
        if args.timing:
            engine = TimingEngine(COMPRESSION_PARAMS, args)
        else:
            engine = OpenScoutEngine(COMPRESSION_PARAMS, args)

        return engine

    runner.run(
        engine_setup,
        OpenScoutEngine.ENGINE_NAME,
        INPUT_QUEUE_MAXSIZE,
        args.port,
        args.tokens,
    )


if __name__ == "__main__":
    main()
