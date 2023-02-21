#!/bin/bash
args=$*
.venv/bin/openscout $args &
.venv/bin/openscout-face-engine $args &
.venv/bin/openscout-object-engine $args
