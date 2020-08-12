// Copyright 2020 Carnegie Mellon University
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//      http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package edu.cmu.cs.gabriel.network;

import android.hardware.Camera;

public class EngineInput {
    final private byte[] frame;
    final private Camera.Parameters parameters;
    private double[] coords = null;

    public EngineInput(byte[] frame, Camera.Parameters parameters, double[] coords) {
        this.frame = frame;
        this.parameters = parameters;
        this.coords = coords;
    }


    public byte[] getFrame() {
        return frame;
    }

    public Camera.Parameters getParameters() {
        return parameters;
    }

    public double[] getCoords() { return coords; }

}
