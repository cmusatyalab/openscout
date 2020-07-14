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

import android.app.Application;
import android.os.Handler;

import java.util.function.Consumer;

import edu.cmu.cs.gabriel.Const;
import edu.cmu.cs.gabriel.GabrielClientActivity;
import edu.cmu.cs.gabriel.client.comm.ServerComm;
import edu.cmu.cs.gabriel.client.results.SendSupplierResult;
import edu.cmu.cs.gabriel.protocol.Protos.ResultWrapper;
import edu.cmu.cs.openscout.R;

public class OpenScoutComm {
    private final ServerComm serverComm;
    private final ErrorConsumer onDisconnect;

    public static OpenScoutComm createOpenScoutComm(
            String endpoint, int port, GabrielClientActivity gabrielClientActivity,
            Handler returnMsgHandler, String tokenLimit) {
        Consumer<ResultWrapper> consumer = new ResultConsumer(
                returnMsgHandler, gabrielClientActivity);
        ErrorConsumer onDisconnect = new ErrorConsumer(returnMsgHandler, gabrielClientActivity);
        ServerComm serverComm;
        Application application = gabrielClientActivity.getApplication();
        if (tokenLimit.equals("None")) {
            serverComm = ServerComm.createServerComm(
                    consumer, endpoint, port, application, onDisconnect);
        } else {
            serverComm = ServerComm.createServerComm(
                    consumer, endpoint, port, application, onDisconnect,
                    Integer.parseInt(tokenLimit));
        }

        return new OpenScoutComm(serverComm, onDisconnect);
    }

    OpenScoutComm(ServerComm serverComm, ErrorConsumer onDisconnect) {
        this.serverComm = serverComm;
        this.onDisconnect = onDisconnect;
    }

    public void sendSupplier(FrameSupplier frameSupplier) {
        if (!this.serverComm.isRunning()) {
            return;
        }

        SendSupplierResult sendSupplierResult = this.serverComm.sendSupplier(
                frameSupplier, Const.SOURCE_NAME);
        if (sendSupplierResult == SendSupplierResult.ERROR_GETTING_TOKEN) {
            this.onDisconnect.showErrorMessage(R.string.token_error);
        }
    }

    public void stop() {
        this.serverComm.stop();
    }
}

