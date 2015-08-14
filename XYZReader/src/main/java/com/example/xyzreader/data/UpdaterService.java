package com.example.xyzreader.data;

import android.app.IntentService;
import android.content.ContentProviderOperation;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.OperationApplicationException;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.Uri;
import android.os.RemoteException;
import android.preference.PreferenceManager;
import android.support.annotation.IntDef;
import android.text.format.Time;
import android.util.Log;

import com.example.xyzreader.R;
import com.example.xyzreader.remote.RemoteEndpointUtil;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayList;

public class UpdaterService extends IntentService {
    private static final String TAG = "UpdaterService";

    public static final String BROADCAST_ACTION_STATE_CHANGE
            = "com.example.xyzreader.intent.action.STATE_CHANGE";
    public static final String EXTRA_REFRESHING
            = "com.example.xyzreader.intent.extra.REFRESHING";

    public static final int ARTICLE_STATUS_OK = 0;
    public static final int ARTICLE_STATUS_SERVER_DOWN = 1;
    public static final int ARTICLE_STATUS_SERVER_INVALID = 2;
    public static final int ARTICLE_STATUS_UNKNOWN = 3;

    @Retention(RetentionPolicy.SOURCE)
    @IntDef({ARTICLE_STATUS_OK, ARTICLE_STATUS_SERVER_DOWN, ARTICLE_STATUS_SERVER_INVALID, ARTICLE_STATUS_UNKNOWN})
    public @interface ArticleStatus {}

    public UpdaterService() {
        super(TAG);
    }

    @Override
    protected void onHandleIntent(Intent intent) {
        Time time = new Time();

        ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
        NetworkInfo ni = cm.getActiveNetworkInfo();
        if (ni == null || !ni.isConnected()) {
            Log.w(TAG, "Not online, not refreshing.");
            return;
        }

        sendStickyBroadcast(
                new Intent(BROADCAST_ACTION_STATE_CHANGE).putExtra(EXTRA_REFRESHING, true));

        // Don't even inspect the intent, we only do one thing, and that's fetch content.
        ArrayList<ContentProviderOperation> cpo = new ArrayList<ContentProviderOperation>();

        Uri dirUri = ItemsContract.Items.buildDirUri();

        // Delete all items
        cpo.add(ContentProviderOperation.newDelete(dirUri).build());

        try {
            JSONArray array = RemoteEndpointUtil.fetchJsonArray(this);
            if (array == null) {
                setArticleStatus(getApplicationContext(), ARTICLE_STATUS_SERVER_INVALID);
                return;
            }

            for (int i = 0; i < array.length(); i++) {
                ContentValues values = new ContentValues();
                JSONObject object = array.getJSONObject(i);
                values.put(ItemsContract.Items.SERVER_ID, object.getString("id" ));
                values.put(ItemsContract.Items.AUTHOR, object.getString("author" ));
                values.put(ItemsContract.Items.TITLE, object.getString("title" ));
                values.put(ItemsContract.Items.BODY, object.getString("body" ));
                values.put(ItemsContract.Items.THUMB_URL, object.getString("thumb" ));
                values.put(ItemsContract.Items.PHOTO_URL, object.getString("photo" ));
                values.put(ItemsContract.Items.ASPECT_RATIO, object.getString("aspect_ratio" ));
                time.parse3339(object.getString("published_date"));
                values.put(ItemsContract.Items.PUBLISHED_DATE, time.toMillis(false));
                cpo.add(ContentProviderOperation.newInsert(dirUri).withValues(values).build());
            }

            getContentResolver().applyBatch(ItemsContract.CONTENT_AUTHORITY, cpo);

            setArticleStatus(getApplicationContext(), ARTICLE_STATUS_OK);

        } catch (JSONException | RemoteException | OperationApplicationException e) {
            Log.e(TAG, "Error updating content.", e);
            setArticleStatus(getApplicationContext(), ARTICLE_STATUS_SERVER_INVALID);
        }

        sendStickyBroadcast(
                new Intent(BROADCAST_ACTION_STATE_CHANGE).putExtra(EXTRA_REFRESHING, false));
    }

    public void setArticleStatus(@ArticleStatus int articleStatus) {
        setArticleStatus(getApplicationContext(), articleStatus);
    }

    private static void setArticleStatus(Context context, @ArticleStatus int articleStatus) {
        SharedPreferences sp = PreferenceManager.getDefaultSharedPreferences(context);
        SharedPreferences.Editor spe = sp.edit();
        spe.putInt(context.getString(R.string.pref_article_status_key), articleStatus);
        spe.commit();
    }

}
