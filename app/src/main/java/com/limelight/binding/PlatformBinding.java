package com.limelight.binding;

import android.content.Context;

import com.limelight.binding.crypto.AndroidCryptoProvider;
import com.limelight.nvstream.http.LimelightCryptoProvider;

public class PlatformBinding {
    public static LimelightCryptoProvider getCryptoProvider(Context c) {
        return new AndroidCryptoProvider(c);
    }
}
