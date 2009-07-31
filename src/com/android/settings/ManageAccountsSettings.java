/*
 * Copyright (C) 2008 The Android Open Source Project
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

package com.android.settings;

import com.android.providers.subscribedfeeds.R;
import com.android.settings.SyncStateCheckBoxPreference;
import com.google.android.collect.Maps;

import android.accounts.AccountManager;
import android.accounts.AuthenticatorDescription;
import android.accounts.Future1;
import android.accounts.Future1Callback;
import android.accounts.OperationCanceledException;
import android.accounts.Account;
import android.accounts.OnAccountsUpdatedListener;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.ActiveSyncInfo;
import android.content.ContentResolver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SyncStatusInfo;
import android.content.SyncAdapterType;
import android.content.SyncStatusObserver;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.net.ConnectivityManager;
import android.os.Bundle;
import android.os.Handler;
import android.text.format.DateFormat;
import android.preference.CheckBoxPreference;
import android.preference.Preference;
import android.preference.PreferenceActivity;
import android.preference.PreferenceCategory;
import android.preference.PreferenceScreen;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.util.Log;

import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

public class ManageAccountsSettings extends AccountPreferenceBase implements View.OnClickListener {
    private static final String MANAGE_ACCOUNTS_CATEGORY_KEY = "manageAccountsCategory";
    private static final String BACKGROUND_DATA_CHECKBOX_KEY = "backgroundDataCheckBox";
    private static final int MENU_SYNC_NOW_ID = Menu.FIRST;
    private static final int MENU_SYNC_CANCEL_ID = Menu.FIRST + 1;
    private static final int DIALOG_DISABLE_BACKGROUND_DATA = 1;

    private CheckBoxPreference mBackgroundDataCheckBox;
    private PreferenceCategory mManageAccountsCategory;
    private String[] mAuthorities;
    private TextView mErrorInfoView;
    private java.text.DateFormat mDateFormat;
    private java.text.DateFormat mTimeFormat;
    private Button mAddAccountButton;

    @Override
    public void onCreate(Bundle icicle) {
        super.onCreate(icicle);

        setContentView(R.layout.manage_accounts_screen);
        addPreferencesFromResource(R.xml.manage_accounts_settings);

        mErrorInfoView = (TextView)findViewById(R.id.sync_settings_error_info);
        mErrorInfoView.setVisibility(View.GONE);
        mErrorInfoView.setCompoundDrawablesWithIntrinsicBounds(
                getResources().getDrawable(R.drawable.ic_list_syncerror), null, null, null);

        mDateFormat = DateFormat.getDateFormat(this);
        mTimeFormat = DateFormat.getTimeFormat(this);

        mBackgroundDataCheckBox = (CheckBoxPreference) findPreference(BACKGROUND_DATA_CHECKBOX_KEY);

        mManageAccountsCategory =
                (PreferenceCategory) findPreference(MANAGE_ACCOUNTS_CATEGORY_KEY);
        mAuthorities = getIntent().getStringArrayExtra(AUTHORITIES_FILTER_KEY);
        mAddAccountButton = (Button) findViewById(R.id.add_account_button);
        mAddAccountButton.setOnClickListener(this);

        AccountManager.get(this).addOnAccountsUpdatedListener(this, null, true);
        updateAuthDescriptions();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        super.onCreateOptionsMenu(menu);
        menu.add(0, MENU_SYNC_NOW_ID, 0, getString(R.string.sync_menu_sync_now))
                .setIcon(com.android.internal.R.drawable.ic_menu_refresh);
        menu.add(0, MENU_SYNC_CANCEL_ID, 0, getString(R.string.sync_menu_sync_cancel))
                .setIcon(android.R.drawable.ic_menu_close_clear_cancel);
        return true;
    }

    @Override
    public boolean onPrepareOptionsMenu(Menu menu) {
        super.onPrepareOptionsMenu(menu);
        boolean syncActive = ContentResolver.getActiveSync() != null;
        menu.findItem(MENU_SYNC_NOW_ID).setVisible(!syncActive);
        menu.findItem(MENU_SYNC_CANCEL_ID).setVisible(syncActive);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case MENU_SYNC_NOW_ID:
                startSyncForEnabledProviders();
                return true;
            case MENU_SYNC_CANCEL_ID:
                cancelSyncForEnabledProviders();
                return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    public boolean onPreferenceTreeClick(PreferenceScreen preferences, Preference preference) {
        if (preference == mBackgroundDataCheckBox) {
            ConnectivityManager connManager =
                    (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            boolean oldBackgroundDataSetting = connManager.getBackgroundDataSetting();
            boolean backgroundDataSetting = mBackgroundDataCheckBox.isChecked();
            if (oldBackgroundDataSetting != backgroundDataSetting) {
                if (backgroundDataSetting) {
                    setBackgroundDataInt(true);
                } else {
                    // This will get unchecked only if the user hits "Ok"
                    mBackgroundDataCheckBox.setChecked(true);
                    showDialog(DIALOG_DISABLE_BACKGROUND_DATA);
                }
            }
        } else if (preference instanceof SyncStateCheckBoxPreference) {
            SyncStateCheckBoxPreference syncPref = (SyncStateCheckBoxPreference) preference;
            String authority = syncPref.getAuthority();
            Account account = syncPref.getAccount();
            if (syncPref.isOneTimeSyncMode()) {
                requestOrCancelSync(account, authority, true);
            } else {
                boolean syncOn = syncPref.isChecked();
                boolean oldSyncState = ContentResolver.getSyncAutomatically(account, authority);
                if (syncOn != oldSyncState) {
                    ContentResolver.setSyncAutomatically(account, authority, syncOn);
                    requestOrCancelSync(account, authority, syncOn);
                }
            }
        } else {
            return false;
        }
        return true;
    }

    @Override
    protected Dialog onCreateDialog(int id) {
        switch (id) {
            case DIALOG_DISABLE_BACKGROUND_DATA:
                final CheckBoxPreference pref =
                    (CheckBoxPreference) findPreference(BACKGROUND_DATA_CHECKBOX_KEY);
                return new AlertDialog.Builder(this)
                        .setTitle(R.string.background_data_dialog_title)
                        .setIcon(android.R.drawable.ic_dialog_alert)
                        .setMessage(R.string.background_data_dialog_message)
                        .setPositiveButton(android.R.string.ok,
                                    new DialogInterface.OnClickListener() {
                                public void onClick(DialogInterface dialog, int which) {
                                    setBackgroundDataInt(false);
                                    pref.setChecked(false);
                                }
                            })
                        .setNegativeButton(android.R.string.cancel, null)
                        .create();
        }

        return null;
    }

    private void setBackgroundDataInt(boolean enabled) {
        ConnectivityManager connManager =
                (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        connManager.setBackgroundDataSetting(enabled);
    }

    private void startSyncForEnabledProviders() {
        requestOrCancelSyncForEnabledProviders(true /* start them */);
    }

    private void cancelSyncForEnabledProviders() {
        requestOrCancelSyncForEnabledProviders(false /* cancel them */);
    }

    private void requestOrCancelSyncForEnabledProviders(boolean startSync) {
        int count = getPreferenceScreen().getPreferenceCount();
        for (int i = 0; i < count; i++) {
            Preference pref = getPreferenceScreen().getPreference(i);
            if (! (pref instanceof SyncStateCheckBoxPreference)) {
                continue;
            }
            SyncStateCheckBoxPreference syncPref = (SyncStateCheckBoxPreference) pref;
            if (!syncPref.isChecked()) {
                continue;
            }
            requestOrCancelSync(syncPref.getAccount(), syncPref.getAuthority(), startSync);
        }
    }

    private void requestOrCancelSync(Account account, String authority, boolean flag) {
        if (flag) {
            Bundle extras = new Bundle();
            extras.putBoolean(ContentResolver.SYNC_EXTRAS_MANUAL, true);
            ContentResolver.requestSync(account, authority, extras);
        } else {
            ContentResolver.cancelSync(account, authority);
        }
    }

    @Override
    protected void onSyncStateUpdated() {
        // Set background connection state
        ConnectivityManager connManager =
                (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        mBackgroundDataCheckBox.setChecked(connManager.getBackgroundDataSetting());

        // iterate over all the preferences, setting the state properly for each
        Date date = new Date();
        ActiveSyncInfo activeSyncValues = ContentResolver.getActiveSync();
        boolean syncIsFailing = false;
        for (int i = 0, count = getPreferenceScreen().getPreferenceCount(); i < count; i++) {
            Preference pref = getPreferenceScreen().getPreference(i);
            if (! (pref instanceof AccountPreference)) {
                continue;
            }

            AccountPreference accountPref = (AccountPreference) pref;
            Account account = accountPref.getAccount();
            boolean syncEnabled = false;
            for (String authority : accountPref.getAuthorities()) {
                SyncStatusInfo status = ContentResolver.getSyncStatus(account, authority);
                syncEnabled |= ContentResolver.getSyncAutomatically(account, authority);
                boolean authorityIsPending = ContentResolver.isSyncPending(account, authority);
                boolean activelySyncing = activeSyncValues != null
                        && activeSyncValues.authority.equals(authority)
                        && activeSyncValues.account.equals(account);
                boolean lastSyncFailed = status != null
                        && syncEnabled
                        && status.lastFailureTime != 0
                        && status.getLastFailureMesgAsInt(0)
                           != ContentResolver.SYNC_ERROR_SYNC_ALREADY_IN_PROGRESS;
                if (lastSyncFailed && !activelySyncing && !authorityIsPending) {
                    syncIsFailing = true;
                }
                final long successEndTime = (status == null) ? 0 : status.lastSuccessTime;
                if (successEndTime != 0) {
                    date.setTime(successEndTime);
                    String dateString = mTimeFormat.format(date);
                    final String timeString = mDateFormat.format(date) + " " + dateString;
                    accountPref.setSummary(timeString);
                } else {
                    accountPref.setSummary("");
                }
            }
            if (syncIsFailing) {
                accountPref.setSyncStatus(AccountPreference.SYNC_ERROR);
            } else {
                accountPref.setSyncStatus(
                        syncEnabled ? AccountPreference.SYNC_ALL_OK : AccountPreference.SYNC_NONE);
            }
        }

        mErrorInfoView.setVisibility(syncIsFailing ? View.VISIBLE : View.GONE);
    }

    public void onAccountsUpdated(Account[] accounts) {

        mManageAccountsCategory.removeAll();

        for (int i = 0, n = accounts.length; i < n; i++) {
            final Account account = accounts[i];
            final ArrayList<String> auths = getAuthoritiesForAccountType(account.mType);

            boolean showAccount = true;
            if (mAuthorities != null) {
                showAccount = false;
                for (String requestedAuthority : mAuthorities) {
                    if (auths.contains(requestedAuthority)) {
                        showAccount = true;
                        break;
                    }
                }
            }

            if (showAccount) {
                Drawable icon = getDrawableForType(account.mType);
                AccountPreference preference = new AccountPreference(this, account, icon, auths);
                preference.setSyncStatus(AccountPreference.SYNC_ALL_OK);
                mManageAccountsCategory.addPreference(preference);
            }
        }
        onSyncStateUpdated();
    }

    @Override
    protected void onAuthDescriptionsUpdated() {
        // Update account icons for all account preference items
        for (int i = 0; i < mManageAccountsCategory.getPreferenceCount(); i++) {
            AccountPreference pref = (AccountPreference) mManageAccountsCategory.getPreference(i);
            pref.setProviderIcon(getDrawableForType(pref.getAccount().mType));
            pref.setSummary(getLabelForType(pref.getAccount().mType));
        }
    }

    public void onClick(View v) {
        if (v == mAddAccountButton) {
            Intent intent = new Intent("android.settings.ADD_ACCOUNT_SETTINGS");
            intent.putExtra(AUTHORITIES_FILTER_KEY, mAuthorities);
            startActivity(intent);
        }
    }
}
