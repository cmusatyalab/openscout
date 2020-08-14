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

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.graphics.YuvImage;
import android.hardware.Camera;

import com.google.protobuf.Any;
import com.google.protobuf.ByteString;

import java.io.ByteArrayOutputStream;
import java.util.function.Supplier;

import edu.cmu.cs.gabriel.Const;
import edu.cmu.cs.gabriel.GabrielClientActivity;
import edu.cmu.cs.gabriel.protocol.Protos.InputFrame;
import edu.cmu.cs.gabriel.protocol.Protos.PayloadType;
import edu.cmu.cs.openscout.Protos;
import edu.cmu.cs.openscout.Protos.Extras;

public class FrameSupplier implements Supplier<InputFrame> {
    private GabrielClientActivity gabrielClientActivity;

    public FrameSupplier(GabrielClientActivity gabrielClientActivity) {
        this.gabrielClientActivity = gabrielClientActivity;
    }

    private static byte[] createFrameData(EngineInput engineInput) {
        Camera.Size cameraImageSize = engineInput.getParameters().getPreviewSize();
        YuvImage image = new YuvImage(engineInput.getFrame(), engineInput.getParameters().getPreviewFormat(),
                cameraImageSize.width, cameraImageSize.height, null);
        ByteArrayOutputStream tmpBuffer = new ByteArrayOutputStream();
        // chooses quality 67 and it roughly matches quality 5 in avconv
        image.compressToJpeg(new Rect(0, 0, image.getWidth(), image.getHeight()),
                67, tmpBuffer);
        if (Const.USING_FRONT_CAMERA) {
            byte[] newFrame = tmpBuffer.toByteArray();
            Bitmap bitmap = BitmapFactory.decodeByteArray(newFrame, 0, newFrame.length);
            ByteArrayOutputStream rotatedStream = new ByteArrayOutputStream();
            Matrix matrix = new Matrix();
            if (Const.FRONT_ROTATION) {
                matrix.postRotate(180);
            }
            matrix.postScale(-1, 1);
            bitmap = Bitmap.createBitmap(bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(),
                    matrix, false);
            bitmap.compress(Bitmap.CompressFormat.JPEG, 67, rotatedStream);
            return rotatedStream.toByteArray();
        } else {
            return tmpBuffer.toByteArray();
        }
    }


    private static InputFrame convertEngineInput(EngineInput engineInput) {
        byte[] frame = FrameSupplier.createFrameData(engineInput);
        Extras extras;
        Extras.Builder extrasBuilder = Extras.newBuilder();
        if (Const.IS_TRAINING) {
            extrasBuilder.setIsTraining(true);
            extrasBuilder.setName(Const.TRAINING_NAME);
        }
        Protos.Location.Builder lb = Protos.Location.newBuilder();
        double [] coords = engineInput.getCoords();
        lb.setLatitude(coords[0]);
        lb.setLongitude(coords[1]);
        extrasBuilder.setLocation(lb);
        extrasBuilder.setClientId(Const.UUID);
        extras = extrasBuilder.build();

        InputFrame inputFrame = InputFrame.newBuilder()
                .setPayloadType(PayloadType.IMAGE)
                .addPayloads(ByteString.copyFrom(frame))
                .setExtras(FrameSupplier.pack(extras))
                .build();
        return inputFrame;
    }

    public InputFrame get() {
        EngineInput engineInput = this.gabrielClientActivity.getEngineInput();
        if (engineInput == null) {
            return null;
        }

        return FrameSupplier.convertEngineInput(engineInput);
    }

    // Based on
    // https://github.com/protocolbuffers/protobuf/blob/master/src/google/protobuf/compiler/java/java_message.cc#L1387
    private static Any pack(Extras engineFields) {
        return Any.newBuilder()
                .setTypeUrl("type.googleapis.com/openscout.Extras")
                .setValue(engineFields.toByteString())
                .build();
    }

}
