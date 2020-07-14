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
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.util.Log;

import java.util.function.Consumer;

import edu.cmu.cs.gabriel.client.results.ErrorType;
import edu.cmu.cs.openscout.R;

public class ErrorConsumer implements Consumer<ErrorType> {
    private static final String TAG = "ErrorConsumer";

    private final Handler returnMsgHandler;
    private final Activity activity;
    private boolean shownError;

    public ErrorConsumer(Handler returnMsgHandler, Activity activity) {
        this.returnMsgHandler = returnMsgHandler;
        this.activity = activity;
        this.shownError = false;
    }

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
        this.showErrorMessage(stringId);
    }

    public void showErrorMessage(int stringId) {
        if (this.shownError) {
            return;
        }

        this.shownError = true;
        Log.i(TAG, "Disconnected");
        Message msg = Message.obtain();
        msg.what = NetworkProtocol.NETWORK_RET_FAILED;
        Bundle data = new Bundle();
        data.putString("message", this.activity.getResources().getString(stringId));
        msg.setData(data);
        this.returnMsgHandler.sendMessage(msg);
    }
}
