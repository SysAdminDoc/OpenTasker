package com.opentasker.core.plugins.locale;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import java.net.URISyntaxException;

/**
 * A setting plugin that answers by Tasker's synchronous protocol. It runs in this test package's own
 * process, the way an installed plugin does, so it's Java: the app's classes and the Kotlin runtime
 * aren't on the classpath there. The "mode" string in its bundle picks how it answers.
 */
public final class SyncSettingFixtureReceiver extends BroadcastReceiver {
    private static final String EXTRA_BUNDLE = "com.twofortyfouram.locale.intent.extra.BUNDLE";
    private static final String EXTRA_COMPLETION_INTENT = "net.dinglisch.android.tasker.extras.COMPLETION_INTENT";
    private static final String EXTRA_RESULT_CODE = "net.dinglisch.android.tasker.extras.RESULT_CODE";
    private static final String EXTRA_VARIABLES = "net.dinglisch.android.tasker.extras.VARIABLES";
    private static final int RESULT_OK = -1;
    private static final int RESULT_FAILED = 2;
    private static final int RESULT_PENDING = 3;

    @Override
    public void onReceive(Context context, Intent intent) {
        Bundle settings = intent.getBundleExtra(EXTRA_BUNDLE);
        String mode = settings == null ? "" : settings.getString("mode", "");
        String completion = intent.getStringExtra(EXTRA_COMPLETION_INTENT);
        if (!isOrderedBroadcast() || completion == null) return;
        switch (mode) {
            case "sync": {
                Bundle extras = new Bundle();
                extras.putBundle(EXTRA_VARIABLES, text("%out", "sync"));
                setResult(RESULT_OK, null, extras);
                break;
            }
            case "pending": {
                setResultCode(RESULT_PENDING);
                Bundle variables = text("%out", "later");
                variables.putStringArray("%list", new String[] {"a", "b"});
                Context app = context.getApplicationContext();
                new Handler(Looper.getMainLooper()).postDelayed(() -> complete(app, completion, RESULT_OK, variables), 300);
                break;
            }
            case "failed":
                setResultCode(RESULT_PENDING);
                complete(context, completion, RESULT_FAILED, text("%errmsg", "disk full"));
                break;
            case "early":
                // Answers through the completion intent and holds the broadcast without saying PENDING.
                complete(context, completion, RESULT_OK, text("%out", "early"));
                SystemClock.sleep(1500);
                break;
            case "silent":
                setResultCode(RESULT_PENDING);
                break;
            default:
                break;
        }
    }

    private static Bundle text(String name, String value) {
        Bundle bundle = new Bundle();
        bundle.putString(name, value);
        return bundle;
    }

    private static void complete(Context context, String uri, int code, Bundle variables) {
        try {
            Intent done = Intent.parseUri(uri, Intent.URI_INTENT_SCHEME);
            done.putExtra(EXTRA_RESULT_CODE, code);
            done.putExtra(EXTRA_VARIABLES, variables);
            context.sendBroadcast(done);
        } catch (URISyntaxException error) {
            throw new IllegalStateException(error);
        }
    }
}
