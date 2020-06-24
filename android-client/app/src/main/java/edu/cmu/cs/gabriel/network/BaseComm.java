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

import edu.cmu.cs.gabriel.client.comm.SendSupplierResult;
import edu.cmu.cs.gabriel.client.function.Consumer;
import edu.cmu.cs.gabriel.protocol.Protos.PayloadType;
import edu.cmu.cs.gabriel.protocol.Protos.ResultWrapper;
import edu.cmu.cs.gabriel.client.comm.ServerCommCore;
import edu.cmu.cs.gabriel.client.comm.ErrorType;
import edu.cmu.cs.openscout.R;


public class BaseComm {
    private static String TAG = "OpenScoutComm";
    private static String FILTER = "openscout";

    ServerCommCore serverCommCore;
    Consumer<ResultWrapper> consumer;
    Consumer<ErrorType> onDisconnect;
    private boolean shownError;
    private Handler returnMsgHandler;
    private Activity activity;

    public BaseComm(final Activity activity, final Handler returnMsgHandler) {

        this.consumer = new Consumer<ResultWrapper>() {
            @Override
            public void accept(ResultWrapper resultWrapper) {
                if (resultWrapper.getResultsCount() != 1) {
                    Log.e(TAG, "Got " + resultWrapper.getResultsCount() + " results in output.");
                    return;
                }

                if (!resultWrapper.getFilterPassed().equals(FILTER)) {
                    Log.e(TAG, "Got result that passed filter " +
                            resultWrapper.getFilterPassed());
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
        };

        this.onDisconnect = new Consumer<ErrorType>() {
            @Override
            public void accept(ErrorType errorType) {
                int stringId;
                switch (errorType) {
                    case SERVER_ERROR:
                        stringId = R.string.server_error;
                        break;
                    case SERVER_DISCONNECTED:
                        stringId = R.string.server_disconnected;
                        break;
                    case COULD_NOT_CONNECT:
                        stringId = R.string.could_not_connect;
                        break;
                    default:
                        stringId = R.string.unspecified_error;
                }
                BaseComm.this.showErrorMessage(stringId);
            }
        };

        this.shownError = false;
        this.activity = activity;
        this.returnMsgHandler = returnMsgHandler;

    }

    public void showErrorMessage(int stringId) {
        if (this.shownError) {
            return;
        }

        BaseComm.this.shownError = true;
        Log.i(TAG, "Disconnected");
        Message msg = Message.obtain();
        msg.what = NetworkProtocol.NETWORK_RET_FAILED;
        Bundle data = new Bundle();
        data.putString("message", activity.getResources().getString(stringId));
        msg.setData(data);
        returnMsgHandler.sendMessage(msg);
    }

    public void sendSupplier(FrameSupplier frameSupplier) {
        if (!this.serverCommCore.isRunning()) {
            return;
        }

        SendSupplierResult sendSupplierResult = this.serverCommCore.sendSupplier(
                frameSupplier, FILTER);
        if (sendSupplierResult == SendSupplierResult.ERROR_GETTING_TOKEN) {
            this.showErrorMessage(R.string.token_error);
        }
    }


    public void stop() {
        this.serverCommCore.stop();
    }
}
