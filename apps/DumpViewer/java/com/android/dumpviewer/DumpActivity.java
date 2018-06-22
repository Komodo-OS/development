/*
 * Copyright (C) 2018 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.android.dumpviewer;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Handler;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.Log;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.ArrayAdapter;
import android.widget.AutoCompleteTextView;
import android.widget.Button;
import android.widget.CheckBox;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

import androidx.appcompat.app.AppCompatActivity;

public class DumpActivity extends AppCompatActivity {
    public static final String TAG = "DumpViewer";

    private static final int MAX_HISTORY_SIZE = 32;
    private static final String SHARED_PREF_NAME = "prefs";

    private static final int MAX_RESULT_SIZE = 1 * 1024 * 1024;

    private final Handler mHandler = new Handler();

    private WebView mWebView;
    private AutoCompleteTextView mAcCommandLine;
    private AutoCompleteTextView mAcBeforeContext;
    private AutoCompleteTextView mAcAfterContext;
    private AutoCompleteTextView mAcHead;
    private AutoCompleteTextView mAcTail;
    private AutoCompleteTextView mAcPattern;
    private AutoCompleteTextView mAcSearchQuery;
    private CheckBox mExtendedGrep;
    private CheckBox mIgnoreCaseGrep;
    private CheckBox mShowLast;

    private Button mExecuteButton;
    private Button mNextButton;
    private Button mPrevButton;

    private AsyncTask<Void, Void, String> mRunningTask;

    private SharedPreferences mPrefs;
    private History mCommandHistory;
    private History mRegexpHistory;
    private History mSearchHistory;

    private static final List<String> DEFAULT_COMMANDS = Arrays.asList(new String[]{
            "dumpsys activity",
            "dumpsys activity activities",
            "dumpsys activity broadcasts",
            "dumpsys activity broadcasts history",
            "dumpsys activity services",
            "dumpsys activity starter",
            "dumpsys activity processes",
            "dumpsys activity recents",
            "dumpsys alarm",
            "dumpsys appops",
            "dumpsys backup",
            "dumpsys battery",
            "dumpsys bluetooth_manager",
            "dumpsys content",
            "dumpsys deviceidle",
            "dumpsys device_policy",
            "dumpsys jobscheduler",
            "dumpsys location",
            "dumpsys meminfo -a",
            "dumpsys netpolicy",
            "dumpsys notification",
            "dumpsys package",
            "dumpsys power",
            "dumpsys procstats",
            "dumpsys settings",
            "dumpsys shortcut",
            "dumpsys usagestats",
            "dumpsys user",

            "dumpsys activity service com.android.systemui/.SystemUIService",
            "dumpsys activity provider com.android.providers.contacts/.ContactsProvider2",
            "dumpsys activity provider com.android.providers.contacts/.CallLogProvider",
            "dumpsys activity provider com.android.providers.calendar.CalendarProvider2",

            "logcat -v uid -b main",
            "logcat -v uid -b all",
            "logcat -v uid -b system",
            "logcat -v uid -b crash",
            "logcat -v uid -b radio",
            "logcat -v uid -b events"
    });

    private InputMethodManager mImm;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_dump);

        mImm = getSystemService(InputMethodManager.class);

        mWebView = findViewById(R.id.webview);
        mWebView.getSettings().setBuiltInZoomControls(true);
        mWebView.getSettings().setLoadWithOverviewMode(true);
        mWebView.getSettings().setUseWideViewPort(true);

        mExecuteButton = findViewById(R.id.start);
        mExecuteButton.setOnClickListener(this::onStartClicked);
        mNextButton = findViewById(R.id.find_next);
        mNextButton.setOnClickListener(this::onFindNextClicked);
        mPrevButton = findViewById(R.id.find_prev);
        mPrevButton.setOnClickListener(this::onFindPrevClicked);

        mAcCommandLine = findViewById(R.id.commandline);
        mAcAfterContext = findViewById(R.id.afterContext);
        mAcBeforeContext = findViewById(R.id.beforeContext);
        mAcHead = findViewById(R.id.head);
        mAcTail = findViewById(R.id.tail);
        mAcPattern = findViewById(R.id.pattern);
        mAcSearchQuery = findViewById(R.id.search);

        mExtendedGrep = findViewById(R.id.extended_pattern);
        mIgnoreCaseGrep = findViewById(R.id.ignore_case);
        mShowLast = findViewById(R.id.scroll_to_bottm);


        mPrefs = getSharedPreferences(SHARED_PREF_NAME, Context.MODE_PRIVATE);
        mCommandHistory = new History(mPrefs, "command_history", MAX_HISTORY_SIZE);
        mCommandHistory.load();
        mRegexpHistory = new History(mPrefs, "regexp_history", MAX_HISTORY_SIZE);
        mRegexpHistory.load();
        mSearchHistory = new History(mPrefs, "search_history", MAX_HISTORY_SIZE);
        mSearchHistory.load();

        setupAutocomplete(mAcBeforeContext, "0", "1", "2", "3", "5", "10");
        setupAutocomplete(mAcAfterContext, "0", "1", "2", "3", "5", "10");
        setupAutocomplete(mAcHead, "0", "100", "1000", "2000");
        setupAutocomplete(mAcTail, "0", "100", "1000", "2000");

        mWebView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                // Apparently we need a small delay for it to work.
                mHandler.postDelayed(DumpActivity.this::onContentLoaded, 200);
            }
        });
        refreshHistory();

        refreshUi();
    }

    private void refreshUi() {
        final boolean canExecute = getCommandLine().length() > 0;
        final boolean canSearch = mAcSearchQuery.getText().length() > 0;

        mExecuteButton.setEnabled(canExecute);
        mNextButton.setEnabled(canSearch);
        mPrevButton.setEnabled(canSearch);
    }

    private void setupAutocomplete(AutoCompleteTextView target, List<String> values) {
        setupAutocomplete(target, values.toArray(new String[values.size()]));
    }

    private void setupAutocomplete(AutoCompleteTextView target, String... values) {
        final ArrayAdapter<String> adapter = new ArrayAdapter<>(this,
                android.R.layout.simple_dropdown_item_1line, values);
        target.setAdapter(adapter);
        target.setOnClickListener((v) -> ((AutoCompleteTextView) v).showDropDown());
        target.setOnFocusChangeListener(this::showAutocompleteDropDown);
        target.addTextChangedListener(
                new TextWatcher() {
                    @Override
                    public void beforeTextChanged(CharSequence s, int start, int count, int after) {
                    }

                    @Override
                    public void onTextChanged(CharSequence s, int start, int before, int count) {
                    }

                    @Override
                    public void afterTextChanged(Editable s) {
                        refreshUi();
                    }
                });
    }

    private void showAutocompleteDropDown(View view, boolean hasFocus) {
        if (hasFocus) {
            final AutoCompleteTextView target = (AutoCompleteTextView) view;
            if (!target.isPopupShowing()) {
                target.showDropDown();
            }
        }
    }

    private void hideIme() {
        mImm.hideSoftInputFromWindow(mAcCommandLine.getWindowToken(), 0);
    }

    private void refreshHistory() {
        // Command line autocomplete.
        final List<String> commands = new ArrayList<>(128);
        mCommandHistory.addAllTo(commands);
        commands.addAll(DEFAULT_COMMANDS);

        setupAutocomplete(mAcCommandLine, commands);

        // Regexp autocomplete
        final List<String> patterns = new ArrayList<>(MAX_HISTORY_SIZE);
        mRegexpHistory.addAllTo(patterns);
        setupAutocomplete(mAcPattern, patterns);

        // Search autocomplete
        final List<String> queries = new ArrayList<>(MAX_HISTORY_SIZE);
        mSearchHistory.addAllTo(queries);
        setupAutocomplete(mAcSearchQuery, queries);
    }

    private String getCommandLine() {
        return mAcCommandLine.getText().toString().trim();
    }

    private void setMessage(String format, Object... args) {
        mHandler.post(() -> setText(String.format(format, args)));
    }

    private void setText(String text) {
        Log.v(TAG, "Trying to set string to webview: length=" + text.length());
        mHandler.post(() -> {
            final StringBuilder sb = new StringBuilder(text.length() * 2);
            sb.append("<html><body style=\"white-space: nowrap;\"><pre>\n");
            char c;
            for (int i = 0; i < text.length(); i++) {
                c = text.charAt(i);
                switch (c) {
                    case '<':
                        sb.append("&lt;");
                        break;
                    case '>':
                        sb.append("&gt;");
                        break;
                    case '&':
                        sb.append("&amp;");
                        break;
                    case '\'':
                        sb.append("&#39;");
                        break;
                    case '"':
                        sb.append("&quot;");
                        break;
                    default:
                        sb.append(c);
                }
            }
            sb.append("</pre></body></html>\n");

            mWebView.loadData(sb.toString(), "text/html", null);
        });
    }

    private void onContentLoaded() {
        if (mShowLast == null) {
            return;
        }
        if (mShowLast.isChecked()) {
            mWebView.pageDown(true /* toBottom */);
        } else {
            mWebView.pageUp(true /* toTop */);
        }
    }

    public void onFindNextClicked(View v) {
        doFindNextOrPrev(true);
    }

    public void onFindPrevClicked(View v) {
        doFindNextOrPrev(false);
    }

    String mLastQuery;

    private void doFindNextOrPrev(boolean next) {
        final String query = mAcSearchQuery.getText().toString();
        if (query.length() == 0) {
            return;
        }
        hideIme();

        mSearchHistory.add(query);

        if (query.equals(mLastQuery)) {
            mWebView.findNext(next);
        } else {
            mWebView.findAllAsync(query);
        }
        mLastQuery = query;
    }

    public void onStartClicked(View v) {
        if (mRunningTask != null) {
            mRunningTask.cancel(true);
        }
        final String command = getCommandLine();
        if (command.length() > 0) {
            startCommand(command);
        }
    }

    private void startCommand(String command) {
        hideIme();

        mCommandHistory.add(command);
        mRegexpHistory.add(mAcPattern.getText().toString().trim());
        (mRunningTask = new Dumper(command)).execute();
        refreshHistory();
    }

    private class Dumper extends AsyncTask<Void, Void, String> {
        final String command;

        public Dumper(String command) {
            this.command = command;
        }

        @Override
        protected String doInBackground(Void... voids) {
            final ByteArrayOutputStream out = new ByteArrayOutputStream(1024 * 1024);
            try {
                try (InputStream is = dump(command)) {
                    final byte[] buf = new byte[1024 * 16];
                    int read;
                    int written = 0;
                    while ((read = is.read(buf)) >= 0) {
                        out.write(buf, 0, read);
                        written += read;
                        if (written >= MAX_RESULT_SIZE) {
                            out.write("\n[Result too long; omitted]".getBytes());
                            break;
                        }
                    }
                }
            } catch (Exception e) {
                setMessage("Caught exception: %s\n%s", e.getMessage(), Log.getStackTraceString(e));
                return null;
            }

            return out.toString();
        }

        @Override
        protected void onCancelled(String s) {
            mRunningTask = null;
        }

        @Override
        protected void onPostExecute(String s) {
            mRunningTask = null;
            if (s != null) {
                if (s.length() == 0) {
                    setText("[No result]");
                } else {
                    setText(s);
                }
            }
        }
    }

    private InputStream dump(String originalCommand)
            throws IOException {
        final String commandLine = buildCommandLine(originalCommand);
        setText("Running: " + commandLine);

        final Process p = Runtime.getRuntime().exec(new String[]{"/bin/sh", "-c", commandLine});
        final InputStream in = p.getInputStream();

        final AtomicBoolean timedOut = new AtomicBoolean();
        final AtomicReference<Throwable> th = new AtomicReference<>();
        new Thread(() -> {
            try {
                Log.v(TAG, "Waiting for process: " + p);
                timedOut.set(!p.waitFor(2, TimeUnit.SECONDS));
                if (timedOut.get()) {
                    setText(String.format("Command %s timed out", commandLine));
                    try {
                        p.destroyForcibly();
                        in.close();
                    } catch (Exception ignore) {
                    }
                } else {
                    Log.v(TAG, String.format("Command %s finished with code %d", commandLine,
                            p.exitValue()));
                }
            } catch (Exception e) {
                th.set(e);
            }
        }).start();

        return in;
    }

    private static final Pattern sLogcat = Pattern.compile("^logcat(\\s|$)");

    private String buildCommandLine(String command) {
        final StringBuilder sb = new StringBuilder(128);
        if (sLogcat.matcher(command).find()) {
            // Make sure logcat command always has -d.
            sb.append("logcat -d ");
            sb.append(command.substring(7));
        } else {
            sb.append(command);
        }

        final int before = Utils.parseInt(mAcBeforeContext.getText().toString(), 0);
        final int after = Utils.parseInt(mAcAfterContext.getText().toString(), 0);
        final int head = Utils.parseInt(mAcHead.getText().toString(), 0);
        final int tail = Utils.parseInt(mAcTail.getText().toString(), 0);

        // Don't trim regexp. Sometimes you want to search for spaces.
        final String regexp = mAcPattern.getText().toString();
        final boolean extended = mExtendedGrep.isChecked();
        final boolean ignoreCase = mIgnoreCaseGrep.isChecked();

        if (regexp.length() > 0) {
            sb.append(" | grep");
            if (extended) {
                sb.append(" -E");
            }
            if (ignoreCase) {
                sb.append(" -i");
            }
            if (before > 0) {
                sb.append(" -B");
                sb.append(before);
            }
            if (after > 0) {
                sb.append(" -A");
                sb.append(after);
            }
            sb.append(" -- ");
            sb.append(Utils.shellEscape(regexp));
        }
        if (head > 0) {
            sb.append(" | head -n ");
            sb.append(head);
        }
        if (tail > 0) {
            sb.append(" | tail -n ");
            sb.append(tail);
        }
        sb.append(" 2>&1");
        return sb.toString();
    }
}