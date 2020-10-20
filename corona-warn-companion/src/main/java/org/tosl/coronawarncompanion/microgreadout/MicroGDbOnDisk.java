package org.tosl.coronawarncompanion.microgreadout;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.res.AssetManager;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.os.Environment;
import android.util.Log;

import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.google.protobuf.ByteString;

import org.iq80.leveldb.CompressionType;
import org.iq80.leveldb.DBFactory;
import org.iq80.leveldb.Options;
import org.iq80.leveldb.impl.Iq80DBFactory;
import org.tosl.coronawarncompanion.CWCApplication;
import org.tosl.coronawarncompanion.gmsreadout.ContactRecordsProtos;
import org.tosl.coronawarncompanion.rpis.RpiList;

import java.io.File;
import java.io.FileFilter;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.regex.Pattern;

import static org.tosl.coronawarncompanion.gmsreadout.Sudo.sudo;
import static org.tosl.coronawarncompanion.tools.Utils.byteArrayToHexString;
import static org.tosl.coronawarncompanion.tools.Utils.getDaysFromMillis;
import static org.tosl.coronawarncompanion.tools.Utils.getDaysFromSeconds;
import static org.tosl.coronawarncompanion.tools.Utils.hexStringToByteArray;

public class MicroGDbOnDisk {

    private static final String TAG = "MicroGDbOnDisk";

    @SuppressLint("SdCardPath")
    private static final String gmsPathStr = "/data/data/com.google.android.gms/databases";
    private static final String dbName = "exposure.db";
    private static final String dbNameModifier = "_";
    private static final String dbNameModified = dbName+dbNameModifier;
    private static String cachePathStr = "";

    private final Context context;

    public MicroGDbOnDisk(Context context) {
        this.context = context;
    }

    public boolean copyFromGMS() {
        // Copy the microG GMS database to local app cache
        Log.d(TAG, "Trying to copy microG database");
        File cacheDir = context.getExternalCacheDir();
        if (cacheDir == null) {
            cacheDir = context.getCacheDir();
        }
        assert cacheDir != null;
        cachePathStr = cacheDir.getPath();

        // First rename the database, then copy it, then rename to the original name
        String result = sudo(
                "rm "+cachePathStr+"/"+dbNameModified,
                "mv "+gmsPathStr+"/"+dbName+" "+gmsPathStr+"/"+dbNameModified,
                "cp "+gmsPathStr+"/"+dbNameModified+" "+cachePathStr+"/",
                "mv "+gmsPathStr+"/"+dbNameModified+" "+gmsPathStr+"/"+dbName,
                "ls -la "+cachePathStr+"/"+dbNameModified
        );
        Log.d(TAG, "Result from trying to copy LevelDB: "+result);
        if (result.length() < 10) {
            Log.e(TAG, "ERROR: Super User rights not granted!");
            return false;
        }
        return true;
    }

    private void copyFile(InputStream in, OutputStream out) throws IOException {
        byte[] buffer = new byte[1024];
        int read;
        while((read = in.read(buffer)) != -1){
            out.write(buffer, 0, read);
        }
    }

    public RpiList getRpisFromContactDB(Activity activity) {
        RpiList rpiList = null;

        if (!copyFromGMS()) {
            return null;
        }

        try (SQLiteDatabase microGDb = SQLiteDatabase.openDatabase(cachePathStr + "/" + dbNameModified,
                null, SQLiteDatabase.OPEN_READONLY)) {

            if (microGDb != null) {
                Log.d(TAG, "Opened microG Database: " + gmsPathStr + "/" + dbNameModified);

                Cursor cursor = microGDb.rawQuery("SELECT rpi, aem, timestamp, rssi, duration "+
                        "FROM advertisements", null);

                rpiList = new RpiList();

                while (cursor.moveToNext()) {
                    // parse entry from table "advertisements"
                    byte[] rpiBytes = cursor.getBlob(0);
                    if (rpiBytes == null) {
                        Log.w(TAG, "Warning: Found rpiBytes == null");
                    } else {
                        byte[] aemBytes = cursor.getBlob(1);
                        if (aemBytes == null) {
                            Log.w(TAG, "Warning: Found aemBytes == null");
                        } else {
                            long timestampMs = cursor.getLong(2);
                            long rssi = cursor.getLong(3);
                            int duration = cursor.getInt(4);

                            Log.d(TAG, "Scan read: " + byteArrayToHexString(rpiBytes) + " " + byteArrayToHexString(aemBytes) +
                                    " RSSI: " + rssi + ", Timestamp: " + timestampMs + ", Duration: " + duration);

                            // limit RSSI, which could be a very large number, because of this bug: https://github.com/microg/android_packages_apps_GmsCore/issues/1230
                            if (rssi < -200L) rssi = -200L;
                            if (rssi > +200L) rssi = +200L;

                            // add scanRecord to contactRecords
                            ContactRecordsProtos.ContactRecords.Builder contactRecordsBuilder =
                                    ContactRecordsProtos.ContactRecords.newBuilder();
                            ContactRecordsProtos.ScanRecord scanRecord = ContactRecordsProtos.ScanRecord.newBuilder()
                                    .setTimestamp((int)(timestampMs/1000L))
                                    .setRssi(rssi)
                                    .setAem(ByteString.copyFrom(aemBytes))
                                    .build();
                            contactRecordsBuilder.addRecord(scanRecord);
                            scanRecord = ContactRecordsProtos.ScanRecord.newBuilder()
                                    .setTimestamp((int)((timestampMs+duration)/1000L))
                                    .setRssi(rssi)
                                    .setAem(ByteString.copyFrom(aemBytes))
                                    .build();
                            contactRecordsBuilder.addRecord(scanRecord);

                            // store entry (incl. contactRecords) in rpiList
                            int daysSinceEpochUTC = getDaysFromMillis(timestampMs);
                            rpiList.addEntry(daysSinceEpochUTC, rpiBytes, contactRecordsBuilder.build());
                            if (getDaysFromMillis(timestampMs+duration) != daysSinceEpochUTC) {  // extremely unlikely
                                rpiList.addEntry(daysSinceEpochUTC + 1, rpiBytes, contactRecordsBuilder.build());
                            }
                        }
                    }
                }
                cursor.close();
            }
        } catch (Exception e) {
            e.printStackTrace();
        }

        return rpiList;
    }
}