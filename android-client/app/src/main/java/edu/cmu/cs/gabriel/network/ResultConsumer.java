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
import android.os.Handler;
import android.os.Message;
import android.util.Log;

import com.google.protobuf.ByteString;

import java.util.function.Consumer;


import edu.cmu.cs.gabriel.GabrielClientActivity;
import edu.cmu.cs.gabriel.protocol.Protos.ResultWrapper;
import edu.cmu.cs.gabriel.protocol.Protos.PayloadType;

public class ResultConsumer implements Consumer<ResultWrapper> {
    private static final String TAG = "ResultConsumer";

    private final Handler returnMsgHandler;
    private final GabrielClientActivity gabrielClientActivity;

    public ResultConsumer(Handler returnMsgHandler, GabrielClientActivity gabrielClientActivity) {
        this.returnMsgHandler = returnMsgHandler;
        this.gabrielClientActivity = gabrielClientActivity;
    }

    @Override
    public void accept(ResultWrapper resultWrapper) {
        if (resultWrapper.getResultsCount() != 1) {
            Log.e(TAG, "Got " + resultWrapper.getResultsCount() + " results in output.");
            return;
        }

        ResultWrapper.Result result = resultWrapper.getResults(0);
        Message msg = Message.obtain();

        if (result.getPayloadType() == PayloadType.IMAGE) {
            Log.e(TAG, "Got result of type " + result.getPayloadType().name());
            ByteString dataString = result.getPayload();

            Bitmap imageFeedback = BitmapFactory.decodeByteArray(
                    dataString.toByteArray(), 0, dataString.size());


            msg.what = NetworkProtocol.NETWORK_RET_IMAGE;
            msg.obj = imageFeedback;
        } else if (result.getPayloadType() == PayloadType.TEXT) {
            ByteString dataString = result.getPayload();
            String results = dataString.toStringUtf8();

            msg.what = NetworkProtocol.NETWORK_RET_TEXT;
            msg.obj = results;
        } else {
            return;
        }

        returnMsgHandler.sendMessage(msg);
    }
}

