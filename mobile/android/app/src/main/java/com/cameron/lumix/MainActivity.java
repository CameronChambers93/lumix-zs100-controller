package com.cameron.lumix;

import android.os.Bundle;

import com.getcapacitor.BridgeActivity;

public class MainActivity extends BridgeActivity {
    @Override
    public void onCreate(Bundle savedInstanceState) {
        registerPlugin(LumixNativePlugin.class);
        super.onCreate(savedInstanceState);
    }
}
