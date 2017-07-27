package com.kunzisoft.remembirthday.provider;

import android.accounts.Account;
import android.content.ContentProviderOperation;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.Context;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Build;
import android.provider.BaseColumns;
import android.provider.CalendarContract;
import android.provider.ContactsContract;
import android.support.annotation.Nullable;
import android.text.TextUtils;
import android.text.format.DateUtils;
import android.util.Log;

import com.kunzisoft.remembirthday.element.CalendarEvent;
import com.kunzisoft.remembirthday.element.Contact;
import com.kunzisoft.remembirthday.element.DateUnknownYear;
import com.kunzisoft.remembirthday.element.Reminder;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.TimeZone;

/**
 * Created by joker on 27/07/17.
 */

public class EventProvider {

    private static final String TAG = "EventProvider";

    /**
     * Get a new ContentProviderOperation to insert an event
     */
    public static ContentProviderOperation insert(Context context, long calendarId,
                                                  CalendarEvent event, @Nullable Contact contact) {
        ContentProviderOperation.Builder builder;

        builder = ContentProviderOperation.newInsert(CalendarProvider.getBirthdayAdapterUri(context, CalendarContract.Events.CONTENT_URI));


        builder.withValue(CalendarContract.Events.CALENDAR_ID, calendarId);
        builder.withValue(CalendarContract.Events.DTSTART, event.getDateStart().getTime());
        builder.withValue(CalendarContract.Events.DTEND, event.getDateStop().getTime());
        if(event.isAllDay()) {
            // ALL_DAY events must be UTC
            builder.withValue(CalendarContract.Events.EVENT_TIMEZONE, "UTC");
            builder.withValue(CalendarContract.Events.ALL_DAY, 1);
        }
        builder.withValue(CalendarContract.Events.TITLE, event.getTitle());
        builder.withValue(CalendarContract.Events.STATUS, CalendarContract.Events.STATUS_CONFIRMED);

        /*
         * Enable reminders for this event
         *
         * Note: Needs to be explicitly set on Android < 4 to enable reminders
         */
        builder.withValue(CalendarContract.Events.HAS_ALARM, 1);

        /*
         * Set availability to free.
         *
         * Note: HTC calendar (4.0.3 Android + HTC Sense 4.0) will show a conflict with other events
         * if availability is not set to free!
         */
        if (Build.VERSION.SDK_INT >= 14) {
            builder.withValue(CalendarContract.Events.AVAILABILITY, CalendarContract.Events.AVAILABILITY_FREE);
        }

        // add button to open contact
        if (Build.VERSION.SDK_INT >= 16 && contact != null && contact.getLookUpKey() != null) {
            builder.withValue(CalendarContract.Events.CUSTOM_APP_PACKAGE, context.getPackageName());
            Uri contactLookupUri = Uri.withAppendedPath(
                    ContactsContract.Contacts.CONTENT_LOOKUP_URI, contact.getLookUpKey());
            builder.withValue(CalendarContract.Events.CUSTOM_APP_URI, contactLookupUri.toString());
        }

        return builder.build();
    }

    /**
     * Get Cursor of contacts with events, but only those from Accounts not in our blacklist!
     * <p/>
     * This is really complicated, because we can't query SQLite directly. We need to use the provided Content Provider
     * and query several times for different tables.
     *
     * @return Cursor over all contacts with events, where accounts are not blacklisted
     */
    public static List<Contact> getContacts(Context context, ContentResolver contentResolver) {
        // 0. get blacklist of Account names from own provider
        HashSet<Account> blacklist = //TODO blacklist ProviderHelper.getAccountBlacklist(context);
                new HashSet<>();

        // HashSet of already added events using generated identifiers to check for duplicates before adding
        HashSet<String> addedEventsIdentifiers = new HashSet<>();

        /*
         * 1. Get all raw contacts with their corresponding Account name and type (only raw contacts get get Account
         * affiliation
         */
        Uri rawContactsUri = ContactsContract.RawContacts.CONTENT_URI;
        String[] rawContactsProjection = new String[]{
                ContactsContract.RawContacts._ID,
                ContactsContract.RawContacts.CONTACT_ID,
                ContactsContract.RawContacts.DISPLAY_NAME_PRIMARY,
                ContactsContract.RawContacts.ACCOUNT_NAME,
                ContactsContract.RawContacts.ACCOUNT_TYPE,};
        Cursor rawContacts = contentResolver.query(rawContactsUri, rawContactsProjection, null, null, null);

        /*
         * 2. Go over all raw contacts and check if the Account is allowed.
         * If Account is allowed, get display name and lookup key and all events for this contact.
         * Build a new MatrixCursor out of this data that can be used.
         */
        String[] columns = new String[]{
                BaseColumns._ID,
                ContactsContract.Data.DISPLAY_NAME,
                ContactsContract.Data.LOOKUP_KEY,
                ContactsContract.CommonDataKinds.Event.START_DATE,
                ContactsContract.CommonDataKinds.Event.TYPE,
                ContactsContract.CommonDataKinds.Event.LABEL
        };
        MatrixCursor matrixCursor = new MatrixCursor(columns);
        int mcIndex = 0;
        try {
            while (rawContacts != null && rawContacts.moveToNext()) {
                long rawId = rawContacts.getLong(rawContacts.getColumnIndex(ContactsContract.RawContacts._ID));
                String accType = rawContacts.getString(rawContacts.getColumnIndex(ContactsContract.RawContacts.ACCOUNT_TYPE));
                String accName = rawContacts.getString(rawContacts.getColumnIndex(ContactsContract.RawContacts.ACCOUNT_NAME));

                /*
                 * 2a. Check if Account is allowed (not blacklisted)
                 */
                boolean addEvent = false;
                if (TextUtils.isEmpty(accType) || TextUtils.isEmpty(accName)) {
                    // Workaround: Simply add events without proper Account
                    addEvent = true;
                } else {
                    Account acc = new Account(accName, accType);

                    if (!blacklist.contains(acc)) {
                        addEvent = true;
                    }
                }

                if (addEvent) {
                    String displayName = null;
                    String lookupKey = null;
                    String startDate;
                    int type;
                    String label;

                    /*
                     * 2b. Get display name and lookup key from normal contact table
                     */
                    String[] displayProjection = new String[]{
                            ContactsContract.Data.RAW_CONTACT_ID,
                            ContactsContract.Data.DISPLAY_NAME,
                            ContactsContract.Data.LOOKUP_KEY,
                    };
                    String displayWhere = ContactsContract.Data.RAW_CONTACT_ID + "= ?";
                    String[] displaySelectionArgs = new String[]{
                            String.valueOf(rawId)
                    };
                    Cursor displayCursor = contentResolver.query(ContactsContract.Data.CONTENT_URI, displayProjection,
                            displayWhere, displaySelectionArgs, null);
                    try {
                        if (displayCursor != null && displayCursor.moveToFirst()) {
                            displayName = displayCursor.getString(displayCursor.getColumnIndex(ContactsContract.Data.DISPLAY_NAME));
                            lookupKey = displayCursor.getString(displayCursor.getColumnIndex(ContactsContract.Data.LOOKUP_KEY));
                        }
                    } finally {
                        if (displayCursor != null && !displayCursor.isClosed())
                            displayCursor.close();
                    }

                    /*
                     * 2c. Get all events for this raw contact.
                     * We don't get this information for the (merged) contact table, but from the raw contact.
                     * If we would query this infos from the contact table we would also get events that should have been filtered!
                     */
                    Uri thisRawContactUri = ContentUris.withAppendedId(ContactsContract.RawContacts.CONTENT_URI, rawId);
                    Uri entityUri = Uri.withAppendedPath(thisRawContactUri, ContactsContract.RawContacts.Entity.CONTENT_DIRECTORY);
                    String[] eventsProjection = new String[]{
                            ContactsContract.RawContacts._ID,
                            ContactsContract.RawContacts.Entity.DATA_ID,
                            ContactsContract.CommonDataKinds.Event.START_DATE,
                            ContactsContract.CommonDataKinds.Event.TYPE,
                            ContactsContract.CommonDataKinds.Event.LABEL
                    };
                    String eventsWhere = ContactsContract.RawContacts.Entity.MIMETYPE + "= ? AND "
                            + ContactsContract.RawContacts.Entity.DATA_ID + " IS NOT NULL";
                    String[] eventsSelectionArgs = new String[]{
                            ContactsContract.CommonDataKinds.Event.CONTENT_ITEM_TYPE
                    };
                    Cursor eventsCursor = contentResolver.query(entityUri, eventsProjection, eventsWhere,
                            eventsSelectionArgs, null);
                    try {
                        while (eventsCursor != null && eventsCursor.moveToNext()) {
                            startDate = eventsCursor.getString(eventsCursor.getColumnIndex(ContactsContract.CommonDataKinds.Event.START_DATE));
                            type = eventsCursor.getInt(eventsCursor.getColumnIndex(ContactsContract.CommonDataKinds.Event.TYPE));
                            label = eventsCursor.getString(eventsCursor.getColumnIndex(ContactsContract.CommonDataKinds.Event.LABEL));

                            /*
                             * 2d. Add this information to our MatrixCursor if not already added previously.
                             *
                             * If two SyncAdapter Accounts have the same contact with duplicated events, the event will already be in
                             * the HashSet addedEventsIdentifiers.
                             *
                             * eventIdentifier does not include startDate, because the String formats of startDate differ between accounts.
                             */
                            String eventIdentifier = lookupKey + type + label;
                            if (addedEventsIdentifiers.contains(eventIdentifier)) {
                                Log.d(TAG, "Event was NOT added, duplicate! Identifier: " + eventIdentifier);
                            } else {
                                Log.d(TAG, "Event was added! Identifier " + eventIdentifier);
                                addedEventsIdentifiers.add(eventIdentifier);

                                matrixCursor.newRow().add(mcIndex).add(displayName).add(lookupKey).add(startDate).add(type).add(label);
                                mcIndex++;
                            }
                        }
                    } finally {
                        if (eventsCursor != null && !eventsCursor.isClosed())
                            eventsCursor.close();
                    }
                }
            }
        } finally {
            if (rawContacts != null && !rawContacts.isClosed())
                rawContacts.close();
        }

        List<Contact> contactList = new ArrayList<>();
        try {
            int eventDateColumn = matrixCursor
                    .getColumnIndex(ContactsContract.CommonDataKinds.Event.START_DATE);
            int displayNameColumn = matrixCursor.getColumnIndex(ContactsContract.Contacts.DISPLAY_NAME);
            int eventTypeColumn = matrixCursor
                    .getColumnIndex(ContactsContract.CommonDataKinds.Event.TYPE);
            int eventCustomLabelColumn = matrixCursor
                    .getColumnIndex(ContactsContract.CommonDataKinds.Event.LABEL);
            int eventLookupKeyColumn = matrixCursor
                    .getColumnIndex(ContactsContract.CommonDataKinds.Event.LOOKUP_KEY);

            // for every event...
            while (matrixCursor.moveToNext()) {
                Contact contact = new Contact(matrixCursor.getString(displayNameColumn));
                contact.setBirthday(DateUnknownYear.stringToDate(matrixCursor.getString(eventDateColumn)));
                //contact.setType(matrixCursor.getInt(eventTypeColumn));
                //contact.setLabel(matrixCursor.getString(eventCustomLabelColumn));
                contact.setLookUpKey(matrixCursor.getString(eventLookupKeyColumn));

                contactList.add(contact);
            }
        } finally {
            if (!matrixCursor.isClosed())
                matrixCursor.close();
        }
        return contactList;
    }

}
