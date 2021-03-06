package com.couchbase.cblite.phonegap;

import android.annotation.SuppressLint;
import android.content.Context;

import com.couchbase.lite.CouchbaseLiteException;
import com.couchbase.lite.Database;
import com.couchbase.lite.Emitter;
import com.couchbase.lite.Manager;
import com.couchbase.lite.Mapper;
import com.couchbase.lite.Query;
import com.couchbase.lite.QueryEnumerator;
import com.couchbase.lite.QueryRow;
import com.couchbase.lite.View;
import com.couchbase.lite.android.AndroidContext;
import com.couchbase.lite.javascript.JavaScriptReplicationFilterCompiler;
import com.couchbase.lite.javascript.JavaScriptViewCompiler;
import com.couchbase.lite.listener.Credentials;
import com.couchbase.lite.listener.LiteListener;
import com.couchbase.lite.router.URLStreamHandlerFactory;
import com.couchbase.lite.util.Log;
import com.google.gson.Gson;

import org.apache.cordova.CallbackContext;
import org.apache.cordova.CordovaInterface;
import org.apache.cordova.CordovaPlugin;
import org.apache.cordova.CordovaWebView;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

public class CBLite extends CordovaPlugin {

    private static final int DEFAULT_LISTEN_PORT = 5984;
    private boolean initFailed = false;
    private int listenPort;
    private Credentials allowedCredentials;

    private Database database = null;
    private LiteListener listener = null;
    private Manager manager;
    private Gson gson = new Gson();
    private List<Map<String, Object>> evaluatedSampleSets = new ArrayList<Map<String, Object>>();
    private List<Map<String, Object>> shownSamplesets = new ArrayList<Map<String, Object>>();
    private Map<String, Object> lastSamples = new HashMap<String, Object>();

    public CBLite() {
        super();
        System.out.println("CBLite() constructor called");
    }

    public void initialize(CordovaInterface cordova, CordovaWebView webView) {
        System.out.println("initialize() called");

        super.initialize(cordova, webView);
        initCBLite();
    }

    private void initCBLite() {
        try {
            allowedCredentials = new Credentials();

            URLStreamHandlerFactory.registerSelfIgnoreError();

            View.setCompiler(new JavaScriptViewCompiler());
            Database.setFilterCompiler(new JavaScriptReplicationFilterCompiler());

            Manager server = startCBLite(this.cordova.getActivity());

            listenPort = startCBLListener(DEFAULT_LISTEN_PORT, server, allowedCredentials);

            System.out.println("initCBLite() completed successfully");

        } catch (final Exception e) {
            e.printStackTrace();
            initFailed = true;
        }

    }

    @Override
    public boolean execute(final String action, final JSONArray args, final CallbackContext callback) {

        System.out.println("--- action ---");
        System.out.println(action);

        if (action.equals("getURL")) {
            return getUrl(callback);
        }

        if (action.equals("getCookedSampleSets")) {
            return getCookedSampleSets(callback, args);
        }

        if (action.equals("getSampleSets")) {
            return getSampleSets(callback, args);
        }

        if (action.equals("getSampleSetById")) {
            return getSampleSetById(callback, args);
        }

        if (action.equals("getSamples")) {
            return getSamples(callback, args);
        }

        if (action.equals("createViews")) {
            return createViews(callback, args);
        }

        return false;
    }

    private boolean createViews(final CallbackContext callback, final JSONArray args) {
        String dbName = "";

        try {
            dbName = args.getString(0);
        } catch (JSONException e) {
            e.printStackTrace();
        }

        final Database database = getDb(dbName);

        createView(database, "sampleset");
        createView(database, "sample");
        callback.success();
        return true;
    }

    private void createView(final Database database, final String view) {
        final View sampleSetView = database.getView(view);

        if (sampleSetView.getMap() == null) {
            sampleSetView.setMap(new Mapper() {
                @Override
                public void map(Map<String, Object> document, Emitter emitter) {
                    String type = String.valueOf(document.get("type"));
                    if (view.equals(type)) {
                        emitter.emit(document.get("_id"), document);
                    }
                }
            }, "1.0");
        }
    }

    private boolean getUrl(final CallbackContext callback) {
        try {

            if (initFailed) {
                callback.error("Failed to initialize couchbase lite.  See console logs");
                return false;
            } else {
                @SuppressLint("DefaultLocale")
                String callbackRespone = String.format(
                        "http://%s:%s@localhost:%d/",
                        allowedCredentials.getLogin(),
                        allowedCredentials.getPassword(),
                        listenPort
                );
                callback.success(callbackRespone);
                return true;
            }

        } catch (final Exception e) {
            e.printStackTrace();
            callback.error(e.getMessage());
        }
        return false;
    }

    private boolean getCookedSampleSets(final CallbackContext callback, final JSONArray args) {

        String dbName = "";
        String mAppName = "";
        Integer mLocationId = -1;

        try {
            dbName = args.getString(0);
            mAppName = args.getString(1);
            mLocationId = args.getInt(2);
        } catch (JSONException e) {
            e.printStackTrace();
        }

        final Database database = getDb(dbName);

        final Query sampleSetsQuery = database.getView("sampleset").createQuery();
        final Query samplesQuery = database.getView("sample").createQuery();
        sampleSetsQuery.setMapOnly(true);
        samplesQuery.setMapOnly(true);

        evaluatedSampleSets = new ArrayList<Map<String, Object>>();
        shownSamplesets = new ArrayList<Map<String, Object>>();
        lastSamples = new HashMap<String, Object>();
        final List<Map<String, Object>> sampleSets = new ArrayList<Map<String, Object>>();
        final List<Map<String, Object>> samples = new ArrayList<Map<String, Object>>();

        try {
            QueryEnumerator samplesResult = samplesQuery.run();

            for (Iterator<QueryRow> it = samplesResult; it.hasNext(); ) {
                QueryRow row = it.next();
                Map<String, Object> sample = ((Map<String, Object>) row.getValue());
                if (sample.get("app_name").equals(mAppName) && sample.get("location_id").equals(mLocationId)) {
                    samples.add(sample);
                }
            }

            QueryEnumerator sampleSetsresult = sampleSetsQuery.run();

            for (Iterator<QueryRow> it = sampleSetsresult; it.hasNext(); ) {
                QueryRow row = it.next();
                Map<String, Object> sampleSet = ((Map<String, Object>) row.getValue());
                if (sampleSet.get("app_name").equals(mAppName) && sampleSet.get("location_id").equals(mLocationId)) {
                    sampleSets.add(sampleSet);
                }
            }

        } catch (CouchbaseLiteException e) {
            callback.error("db error");
            e.printStackTrace();
            callback.error("");
            return false;
        }

        buildLastSamples(samples);
        separateNotEvaluatedSampleSets(sampleSets, samples);
        for (Map<String, Object> sampleSet : shownSamplesets) {
            Map<String, Object> content = (Map<String, Object>) sampleSet.get("content");

            Date lastEvaluationDate = new Date();
            lastEvaluationDate.setDate(lastEvaluationDate.getDate() - 2);
            content.put("last_evaluation", lastEvaluationDate.getTime());
            long nextEvaluation = (new Date()).getTime();
            content.put("next_evaluation", nextEvaluation);

            sampleSet.put("content", content);
        }
        buildShownSampleSets(evaluatedSampleSets);

        final JSONArray result = new JSONArray(shownSamplesets);
        callback.success(result);
        return true;
    }

    private boolean getSampleSetById(final CallbackContext callback, final JSONArray args) {

        String dbName = "";
        String mAppName = "";
        Integer mLocationId = -1;
        String docId = "";

        try {
            dbName = args.getString(0);
            mAppName = args.getString(1);
            mLocationId = args.getInt(2);
            docId = args.getString(3);
        } catch (JSONException e) {
            e.printStackTrace();
        }

        final Database database = getDb(dbName);

        final Query sampleSetsQuery = database.getView("sampleset").createQuery();
        final Query samplesQuery = database.getView("sample").createQuery();
        sampleSetsQuery.setMapOnly(true);
        samplesQuery.setMapOnly(true);

        Map<String, Object> sampleSet = null;
        final List<Map<String, Object>> samples = new ArrayList<Map<String, Object>>();

        try {
            QueryEnumerator samplesResult = samplesQuery.run();

            for (Iterator<QueryRow> it = samplesResult; it.hasNext(); ) {
                QueryRow row = it.next();
                Map<String, Object> sample = ((Map<String, Object>) row.getValue());
                if (sample.get("app_name").equals(mAppName) && sample.get("location_id").equals(mLocationId)) {
                    samples.add(sample);
                }
            }

            QueryEnumerator sampleSetsresult = sampleSetsQuery.run();

            for (Iterator<QueryRow> it = sampleSetsresult; it.hasNext(); ) {
                QueryRow row = it.next();
                Map<String, Object> mSampleSet = ((Map<String, Object>) row.getValue());
                String sampleSetId = row.getDocumentId();
                if (mSampleSet.get("app_name").equals(mAppName) && mSampleSet.get("location_id").equals(mLocationId) && sampleSetId.equals(docId)) {
                    sampleSet = mSampleSet;
                    break;
                }
            }

        } catch (CouchbaseLiteException e) {
            callback.error("db error");
            e.printStackTrace();
            callback.error("");
            return false;
        }

        if (sampleSet == null) {
            callback.error("No sampleSet found");
            return false;
        }

        sampleSet = mapLastSample(samples, sampleSet);

        final JSONObject result = new JSONObject(sampleSet);
        callback.success(result);
        return true;
    }

    private Map<String, Object> getLastSample(final List<Map<String, Object>> samples, final String parentId) {

        if (samples == null) {
            return null;
        }

        if (samples.size() == 1) {
            return samples.get(0);
        }

        Map<String, Object> lastSample = null;
        for (Map<String, Object> sample : samples) {
            String mParentId = String.valueOf(sample.get("parent_id"));
            if (!parentId.equals(mParentId)) {
                continue;
            }
            long lastSampleCreationDate = 0L;
            if (lastSample != null) {
                lastSampleCreationDate = Double.valueOf(String.valueOf(lastSample.get("creation_date"))).longValue();
            }
            long sampleCreationDate = Double.valueOf(String.valueOf(sample.get("creation_date"))).longValue();
            if (lastSample == null || lastSampleCreationDate < sampleCreationDate) {
                lastSample = sample;
            }
        }
        return lastSample;
    }

    private Map<String, Object> mapLastSample(final List<Map<String, Object>> samples, final Map<String, Object> sampleSet) {
        String parentId = String.valueOf(sampleSet.get("_id"));
        Map<String, Object> lastSample = getLastSample(samples, parentId);
        Map<String, Object> content = (Map<String, Object>) sampleSet.get("content");

        boolean disposed = false;
        boolean readyForDisposal = false;
        boolean eol = false;
        String endOfLife = "";
        String eolReason = "";
        Date beforeToday = new Date();
        beforeToday.setDate(beforeToday.getDate() - 2);
        long lastEvaluation = beforeToday.getTime();
        long nextEvaluation = (new Date()).getTime();

        if (lastSample != null) {
            Map<String, Object> lastSampleContent = (Map<String, Object>) lastSample.get("content");

            disposed = Boolean.valueOf(String.valueOf(lastSampleContent.get("disposed"))) ?
                    Boolean.valueOf(String.valueOf(lastSampleContent.get("disposed"))) :
                    Boolean.valueOf(String.valueOf(content.get("disposed")));


            readyForDisposal = Boolean.valueOf(String.valueOf(lastSampleContent.get("ready_for_disposal"))) ?
                    Boolean.valueOf(String.valueOf(lastSampleContent.get("ready_for_disposal"))) :
                    Boolean.valueOf(String.valueOf(content.get("ready_for_disposal")));

            lastEvaluation = Double.valueOf(String.valueOf(lastSample.get("creation_date"))).longValue();
            Date nextEvaluationDate = new Date(lastEvaluation);
            nextEvaluationDate.setDate(nextEvaluationDate.getDate() + 1);
            nextEvaluation = nextEvaluationDate.getTime();
            endOfLife = String.valueOf(lastSample.get("End-of-Life"));
            eol = Boolean.valueOf(String.valueOf(lastSample.get("eol")));
            eolReason = String.valueOf(lastSample.get("eolReason"));
        }

        content.put("disposed", disposed);
        content.put("ready_for_disposal", readyForDisposal);
        content.put("last_evaluation", lastEvaluation);
        content.put("next_evaluation", nextEvaluation);
        content.put("End-of-Life", endOfLife);
        content.put("eol", eol);
        content.put("eolReason", eolReason);
        sampleSet.put("content", content);

        return sampleSet;
    }

    private void buildLastSamples(final List<Map<String, Object>> samples) {
        for (Map<String, Object> sample : samples) {
            String parentId = String.valueOf(sample.get("parent_id"));
            Map<String, Object> lastSample = (Map<String, Object>) lastSamples.get(parentId);
            long lastSampleCreationDate = 0L;

            try {
                if (lastSample != null) {
                    lastSampleCreationDate = Double.valueOf(String.valueOf(lastSample.get("creation_date"))).longValue();
                }

                long sampleCreationDate = Double.valueOf(String.valueOf(sample.get("creation_date"))).longValue();
                if (lastSample == null || lastSampleCreationDate < sampleCreationDate) {
                    lastSamples.put(parentId, sample);
                }
            } catch (Exception e) {
                e.printStackTrace();
            }

        }
    }

    private void separateNotEvaluatedSampleSets(final List<Map<String, Object>> sampleSets, final List<Map<String, Object>> samples) {
        List<Map<String, Object>> sampleSetsCopy = new ArrayList<Map<String, Object>>(sampleSets);
        for (Map<String, Object> sampleSet : sampleSetsCopy) {
            String parentId = String.valueOf(sampleSet.get("_id"));
            boolean wasEvaluated = findSampleSet(samples, parentId);
            if (wasEvaluated) {
                evaluatedSampleSets.add(sampleSet);
            } else {
                shownSamplesets.add(sampleSet);
            }
        }
    }

    private boolean findSampleSet(final List<Map<String, Object>> samples, final String parentId) {
        for (Map<String, Object> sample : samples) {
            String mParentId = String.valueOf(sample.get("parent_id"));
            if (mParentId.equals(parentId)) {
                return true;
            }
        }
        return false;
    }

    private void buildShownSampleSets(final List<Map<String, Object>> sampleSets) {
        for (Map<String, Object> sampleSet : sampleSets) {
            mapAndPushLastSample(sampleSet);
        }
    }

    private void mapAndPushLastSample(final Map<String, Object> sampleSet) {

        String parentId = String.valueOf(sampleSet.get("_id"));
        Map<String, Object> lastSample = (Map<String, Object>) lastSamples.get(parentId);
        Map<String, Object> content = (Map<String, Object>) sampleSet.get("content");

        boolean disposed = false;
        boolean readyForDisposal = false;
        boolean eol = false;
        String endOfLife = "";
        String eolReason = "";
        Date beforeToday = new Date();
        beforeToday.setDate(beforeToday.getDate() - 2);
        long lastEvaluation = beforeToday.getTime();
        long nextEvaluation = (new Date()).getTime();

        if (lastSample != null) {
            Map<String, Object> lastSampleContent = (Map<String, Object>) lastSample.get("content");

            disposed = Boolean.valueOf(String.valueOf(lastSampleContent.get("disposed"))) ?
                    Boolean.valueOf(String.valueOf(lastSampleContent.get("disposed"))) :
                    Boolean.valueOf(String.valueOf(content.get("disposed")));


            readyForDisposal = Boolean.valueOf(String.valueOf(lastSampleContent.get("ready_for_disposal"))) ?
                    Boolean.valueOf(String.valueOf(lastSampleContent.get("ready_for_disposal"))) :
                    Boolean.valueOf(String.valueOf(content.get("ready_for_disposal")));


            lastEvaluation = Double.valueOf(String.valueOf(lastSample.get("creation_date"))).longValue();
            Date nextEvaluationDate = new Date(lastEvaluation);
            nextEvaluationDate.setDate(nextEvaluationDate.getDate() + 1);
            nextEvaluation = nextEvaluationDate.getTime();
            endOfLife = String.valueOf(lastSample.get("End-of-Life"));
            eol = Boolean.valueOf(String.valueOf(lastSample.get("eol")));
            eolReason = String.valueOf(lastSample.get("eolReason"));
        }

        content.put("disposed", disposed);
        content.put("ready_for_disposal", readyForDisposal);
        content.put("last_evaluation", lastEvaluation);
        content.put("next_evaluation", nextEvaluation);
        content.put("End-of-Life", endOfLife);
        content.put("eol", eol);
        content.put("eolReason", eolReason);
        sampleSet.put("content", content);

        shownSamplesets.add(sampleSet);
    }

    private boolean getSampleSets(final CallbackContext callback, final JSONArray args) {

        String viewName = "sampleset";
        String dbName = "";
        String mAppName = "";
        Integer mLocationId = -1;

        try {
            dbName = args.getString(0);
            mAppName = args.getString(1);
            mLocationId = args.getInt(2);
        } catch (JSONException e) {
            e.printStackTrace();
        }

        final Database database = getDb(dbName);

        Query query = database.getView(viewName).createQuery();
        query.setMapOnly(true);

        try {
            QueryEnumerator result = query.run();
            JSONArray sampleSetsResult = new JSONArray();
            for (Iterator<QueryRow> it = result; it.hasNext(); ) {
                QueryRow row = it.next();
                Map<String, Object> obj = ((Map<String, Object>) row.getValue());
                if (obj.get("app_name").equals(mAppName) && obj.get("location_id").equals(mLocationId)) {
                    String json = gson.toJson(row.getKey());
                    JSONObject sampleSet = new JSONObject(json);
                    sampleSetsResult.put(sampleSet);
                }
            }
            callback.success(sampleSetsResult);
            return true;
        } catch (CouchbaseLiteException e) {
            callback.error("db error");
            e.printStackTrace();
        } catch (JSONException e) {
            callback.error("json parse error");
            e.printStackTrace();
        }


        return false;
    }

    private boolean getSamples(final CallbackContext callback, final JSONArray args) {

        String viewName = "sample";
        String dbName = "";
        String mAppName = "";
        Integer mLocationId = -1;

        try {
            dbName = args.getString(0);
            mAppName = args.getString(1);
            mLocationId = args.getInt(2);
        } catch (JSONException e) {
            e.printStackTrace();
        }

        final Database database = getDb(dbName);

        Query query = database.getView(viewName).createQuery();
        query.setMapOnly(true);

        try {
            QueryEnumerator result = query.run();
            JSONArray sampleSetsResult = new JSONArray();
            for (Iterator<QueryRow> it = result; it.hasNext(); ) {
                QueryRow row = it.next();
                Map<String, Object> obj = ((Map<String, Object>) row.getValue());
                if (obj.get("app_name").equals(mAppName) && obj.get("location_id").equals(mLocationId)) {
                    String json = gson.toJson(row.getKey());
                    JSONObject sampleSet = new JSONObject(json);
                    sampleSetsResult.put(sampleSet);
                }
            }
            callback.success(sampleSetsResult);
            return true;
        } catch (CouchbaseLiteException e) {
            callback.error("db error");
            e.printStackTrace();
        } catch (JSONException e) {
            callback.error("json parse error");
            e.printStackTrace();
        }


        return false;
    }

    private Database getDb(final String dbName) {
        try {
            database = manager.getExistingDatabase(dbName);
        } catch (CouchbaseLiteException e) {
            e.printStackTrace();
        }
        return database;
    }

    protected Manager startCBLite(final Context context) {
        try {
            Manager.enableLogging(Log.TAG, Log.VERBOSE);
            Manager.enableLogging(Log.TAG_SYNC, Log.VERBOSE);
            Manager.enableLogging(Log.TAG_QUERY, Log.VERBOSE);
            Manager.enableLogging(Log.TAG_VIEW, Log.VERBOSE);
            Manager.enableLogging(Log.TAG_CHANGE_TRACKER, Log.VERBOSE);
            Manager.enableLogging(Log.TAG_BLOB_STORE, Log.VERBOSE);
            Manager.enableLogging(Log.TAG_DATABASE, Log.VERBOSE);
            Manager.enableLogging(Log.TAG_LISTENER, Log.VERBOSE);
            Manager.enableLogging(Log.TAG_MULTI_STREAM_WRITER, Log.VERBOSE);
            Manager.enableLogging(Log.TAG_REMOTE_REQUEST, Log.VERBOSE);
            Manager.enableLogging(Log.TAG_ROUTER, Log.VERBOSE);
            manager = new Manager(new AndroidContext(context), Manager.DEFAULT_OPTIONS);

        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        return manager;
    }

    private int startCBLListener(final int listenPort, final Manager manager, final Credentials allowedCredentials) {
        listener = new LiteListener(manager, listenPort, allowedCredentials);
        int boundPort = listener.getListenPort();
        Thread thread = new Thread(listener);
        thread.start();
        return boundPort;
    }

    public void onResume(final boolean multitasking) {
        System.out.println("CBLite.onResume() called");
    }

    public void onPause(final boolean multitasking) {
        System.out.println("CBLite.onPause() called");
    }

    public void onDestroy() {
        System.out.println("CBLite.onDestroy() called");
        listener.stop();
    }

}