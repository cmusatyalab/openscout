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
import android.os.Handler;

import edu.cmu.cs.gabriel.client.comm.ServerComm;

public class OpenScoutComm extends BaseComm {
    public OpenScoutComm(
            String serverURL, final Activity activity, final Handler returnMsgHandler,
            String tokenLimit) {
        super(activity, returnMsgHandler);

        if (tokenLimit.equals("None")) {
            this.serverCommCore = new ServerComm(this.consumer, this.onDisconnect, serverURL,
                    activity.getApplication());
        } else {
            this.serverCommCore = new ServerComm(this.consumer, this.onDisconnect, serverURL,
                    activity.getApplication(), Integer.parseInt(tokenLimit));
        }
    }
}
