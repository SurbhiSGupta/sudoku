package com.bodaijewels.sudoku;

import android.os.Bundle;
import com.getcapacitor.BridgeActivity;

public class MainActivity extends BridgeActivity {

    @Override
    public void onCreate(Bundle savedInstanceState) {
        // Bug reports and focus mode (BodaiPlugin.java)
        registerPlugin(BodaiPlugin.class);
        super.onCreate(savedInstanceState);
    }
}
