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

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.util.Log;

import com.google.protobuf.ByteString;
import com.google.protobuf.InvalidProtocolBufferException;

import edu.cmu.cs.gabriel.client.function.Consumer;
import edu.cmu.cs.gabriel.protocol.Protos.PayloadType;
import edu.cmu.cs.gabriel.protocol.Protos.ResultWrapper;
import edu.cmu.cs.openscout.Protos.EngineFields;
import edu.cmu.cs.gabriel.client.comm.ServerCommCore;
import edu.cmu.cs.openscout.R;


public class BaseComm {
    private static String TAG = "OpenScoutComm";
    private static String ENGINE_NAME = "openscout";

    ServerCommCore serverCommCore;
    Consumer<ResultWrapper> consumer;
    Runnable onDisconnect;
    private boolean shownError;

    public BaseComm(final Activity activity, final Handler returnMsgHandler) {

        this.consumer = new Consumer<ResultWrapper>() {
            @Override
            public void accept(ResultWrapper resultWrapper) {
                if (resultWrapper.getResultsCount() != 1) {
                    Log.e(TAG, "Got " + resultWrapper.getResultsCount() + " results in output.");
                    return;
                }

                ResultWrapper.Result result = resultWrapper.getResults(0);
                try {
                    EngineFields ef = EngineFields.parseFrom(resultWrapper.getEngineFields().getValue());

                }  catch (InvalidProtocolBufferException e) {
                    e.printStackTrace();
                }

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

                if (!result.getEngineName().equals(ENGINE_NAME)) {
                    Log.e(TAG, "Got result from engine " + result.getEngineName());
                    return;
                }


                returnMsgHandler.sendMessage(msg);
            }
        };

        this.onDisconnect = new Runnable() {
            @Override
            public void run() {
                Log.i(TAG, "Disconnected");
                String message = BaseComm.this.serverCommCore.isRunning()
                        ? activity.getResources().getString(R.string.server_disconnected)
                        : activity.getResources().getString(R.string.could_not_connect);

                if (BaseComm.this.shownError) {
                    return;
                }

                BaseComm.this.shownError = true;

                Message msg = Message.obtain();
                msg.what = NetworkProtocol.NETWORK_RET_FAILED;
                Bundle data = new Bundle();
                data.putString("message", message);
                msg.setData(data);
                returnMsgHandler.sendMessage(msg);
            }
        };

        this.shownError = false;
    }

    public void sendSupplier(FrameSupplier frameSupplier) {
        this.serverCommCore.sendSupplier(frameSupplier);
    }

    public void stop() {
        this.serverCommCore.stop();
    }
}
