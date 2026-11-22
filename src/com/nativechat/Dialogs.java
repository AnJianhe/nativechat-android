package com.nativechat;

import android.app.AlertDialog;
import android.content.Context;

/** Central dialog styling; retain standard alert semantics and accessibility. */
public final class Dialogs {
    private Dialogs() { }
    public static AlertDialog.Builder builder(Context context) {
        return new AlertDialog.Builder(context,
            Palette.of(context).dark ? R.style.NativeDialogDark : R.style.NativeDialogLight);
    }
}
