package edu.cmu.cs.openscout;

public class TimingServerListActivity extends ServerListActivity {
    ServerListAdapter createServerListAdapter() {
        return new TimingServerListAdapter(getApplicationContext(), ItemModelList);
    }
}
