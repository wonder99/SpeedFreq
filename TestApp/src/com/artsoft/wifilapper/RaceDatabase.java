// Copyright 2011-2012, Art Hare
// This file is part of WifiLapper.

//WifiLapper is free software: you can redistribute it and/or modify
//it under the terms of the GNU General Public License as published by
//the Free Software Foundation, either version 3 of the License, or
//(at your option) any later version.

//WifiLapper is distributed in the hope that it will be useful,
//but WITHOUT ANY WARRANTY; without even the implied warranty of
//MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
//GNU General Public License for more details.

//You should have received a copy of the GNU General Public License
//along with WifiLapper.  If not, see <http://www.gnu.org/licenses/>.

package com.artsoft.wifilapper;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Vector;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import com.artsoft.wifilapper.LapAccumulator.LapAccumulatorParams;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteException;
import android.database.sqlite.SQLiteDatabase.CursorFactory;
import android.graphics.Bitmap;
import android.graphics.Bitmap.CompressFormat;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.location.Location;
import android.os.AsyncTask;
import android.os.Environment;
import android.os.Handler;
import android.os.Message;
import android.text.TextUtils;
import android.util.Base64;
import android.util.Log;
import android.widget.Toast;

public class RaceDatabase extends BetterOpenHelper
{
	public RaceDatabase(Context context, String strPath, CursorFactory factory,int version) 
	{
		super(context, strPath, factory, version);
		m_context = context;
	}

	private static final boolean bImageMode = false;
	private static RaceDatabase g_raceDB = null;
	
	private static Context m_context;
	// ahare: 1->2 clearing shit in debug
	// ahare: 2->3 adding start/finish lines to races
	// ahare: 3->14 making races table actually work, added laps and points tables
	// 14->15; removed internal lapid.  The DB lapid will be sufficiently unique
	// 15->16: messed everything up, need freshness
	// 16->17: adding accelerometer data in DB
	// 20->21: adding "point-to-point" member in races table
	// 23->24: adding tracks table
	private static final int m_iVersion = 24;
	private static final String DATABASE_NAME_INTERNAL = "sfraces";
	private static       String strDBFileName;
	public static final String KEY_RACENAME = "name";
	public static final String KEY_LAPCOUNT = "lapcount";
	public static final String KEY_RACEID = "raceid";
	public static final String KEY_LAPTIME = "laptime";
	public static final String KEY_STARTTIME = "starttime";
	public static final String KEY_P2P = "p2p";
	public static final String KEY_FINISHCOUNT = "finishcount";
	private static final int MSG_DOWNLOAD_COMPLETE = 252;
	
	public static int getVersion()
	{
		return m_iVersion;
	}
	public static String getPath()
	{
		return strDBFileName;
	}
	public static boolean CreateInternal(Context ctx, String strBasePath)
	{
		return CreateOnPath(ctx, strBasePath + "/" + DATABASE_NAME_INTERNAL);
	}
	public static String GetExternalDir(Context ctx)
	{
		// First, see if removable storage can be used
		String strPath = null;
	    String strStorage = System.getenv("SECONDARY_STORAGE");
	    if (!TextUtils.isEmpty(strStorage)) {
	        String[] paths = strStorage.split(":");
	        for (String path : paths) {
	            File file = new File(path);
	            if (file.isDirectory() && file.canWrite() ) {
	                strPath=file.toString();
	                break;
	            }
	        }
	    }
	    
	    // If not, default to sdcard0, which is often non-removable, but at least accessible by other apps
	    if( strPath == null )
	    	strPath = Environment.getExternalStorageDirectory().toString();
	    
	    if( BuildConfig.DEBUG && strPath == null )
	    	throw new AssertionError("Can't find any external storage!");
	    
	    return strPath;
	}

	public static boolean CreateExternal(Context ctx)
	{
	    // Create the directory if it doesn't exist
	    String strPath = GetExternalDir(ctx) + "/SpeedFreq";
	    File fileTest = new File(strPath);
	    if ( !fileTest.isDirectory() )
	    	fileTest.mkdir();
	    
		return CreateOnPath(ctx, strPath + "/" + DATABASE_NAME_INTERNAL);
	}
	
	public static boolean CreateOnPath(Context ctx, String strPath)
	{
		strDBFileName = strPath;
		RaceDatabase race = new RaceDatabase(ctx, strDBFileName, null, m_iVersion);
		if(race.m_db != null)
		{
			if(g_raceDB != null && g_raceDB.m_db != null)
			{
				g_raceDB.m_db.close();
			}
			g_raceDB = race;
		}
		return race.m_db != null;
	}
	public synchronized static SQLiteDatabase Get()
	{
		if( g_raceDB != null )
			return g_raceDB.getWritableDatabase();
		else 
			return null;
	}
	public static class download extends AsyncTask<Handler, Void, Integer> {
		Handler m_handler=null;
		protected Integer doInBackground(Handler...h) {
			m_handler = h[0];

			int result=downloadFile("https://www.dropbox.com/s/x37l9skm9ecf8ma/speedfreq.tracks?dl=1");
			return result;

		}
		
	    protected void onPostExecute(Integer result) {
	    	Message msg = new Message();
	    	msg.what = MSG_DOWNLOAD_COMPLETE;
	    	if( result == 0 )
	    	{
	    		msg.arg1 = 0;
	    	}
	    	else
	    		msg.arg1 = result;
    		m_handler.sendMessage(msg);
	    }
	}

	public static int downloadFile(String sUrl) {
		InputStream input = null;
		OutputStream output = null;
		HttpURLConnection connection = null;
		String strDownloadFile;
		//		File fTarget = new File(strDownloadFile);
		File fTarget=null ;
		String strImportedDB=null;
		try {
			File outputDir = m_context.getCacheDir(); // context being the Activity pointer
			fTarget = File.createTempFile("tmpdnld", "", outputDir);
			fTarget.deleteOnExit();
			strDownloadFile = new String(fTarget.getAbsolutePath());// + "/" + fTarget.getName());

			URL url = new URL(sUrl);
			connection = (HttpURLConnection) url.openConnection();
			connection.connect();

			// expect HTTP 200 OK, so we don't mistakenly save error report
			// instead of the file
			if (connection.getResponseCode() != HttpURLConnection.HTTP_OK) {
				return -1;//"Server returned HTTP " + connection.getResponseCode()+ " " + connection.getResponseMessage();
			}

			// download the file
			input = connection.getInputStream();

			output = new FileOutputStream(strDownloadFile);

			byte data[] = new byte[4096];
			int count;
			while ((count = input.read(data)) != -1) {
				output.write(data, 0, count);
			}
		} catch (Exception e) {
			return -2;
		} finally {
			try {
				if (output != null)
					output.close();
				if (input != null)
					input.close();
			} catch (IOException ignored) {
			}

			if (connection != null)
				connection.disconnect();
		}

		if( false ) // don't zip for now
		{
			ZipInputStream zin=null;

			try {
				fTarget = File.createTempFile("tmpdb", null);
				fTarget.deleteOnExit();
				strImportedDB = fTarget.getAbsolutePath().substring(0, fTarget.getAbsolutePath().lastIndexOf("/"));

				zin = new ZipInputStream(new FileInputStream(strDownloadFile));
				ZipEntry ze = null;
				while ((ze = zin.getNextEntry()) != null) {
					strImportedDB = strImportedDB + "/" + ze.getName();
					if (ze.isDirectory()) {
						File unzipFile = new File(strImportedDB);
						if(!unzipFile.isDirectory()) {
							unzipFile.mkdirs();
						}
					}
					else {
						FileOutputStream fout = new FileOutputStream(strImportedDB, false);
						try {
							for (int c = zin.read(); c != -1; c = zin.read()) {
								fout.write(c);
							}
							zin.closeEntry();
						}
						finally {
							fout.close();
						}
					}
				}
				zin.close();
			} catch (FileNotFoundException e) {
				e.printStackTrace();
			} catch (IOException e) {
				e.printStackTrace();
			}
		}
		else
			strImportedDB = strDownloadFile;

		Get().execSQL("attach DATABASE '" + strImportedDB + "' as DNLD_TRACK_DB " );
		Get().execSQL("insert into tracks select null,name, date, testmode, x1,y1,x2,y2,x3,y3,x4,y4,x5,y5,x6,y6, " +
				"vx1,vy1,vx2,vy2,vx3,vy3, p2p, finishcount, image from DNLD_TRACK_DB.tracks");
		Get().execSQL("detach DATABASE DNLD_TRACK_DB");

		// Now keep the lowest ID-numbered track of a given name.  Downloaded tracks have higher numbers, so precedence given to user-generated of same name
		Get().execSQL("delete from tracks where _id not in (select min(_id) from tracks group by name)");
		
		if( fTarget != null )
		fTarget.delete();

		return 0;
	}

	public synchronized static long CreateRaceIfNotExist(SQLiteDatabase db, String strRaceName, LapAccumulator.LapAccumulatorParams lapParams, boolean fTestMode, boolean fP2P, int iFinishCount)
	{
		if(db == null)
		{
			Toast.makeText(null, "Wifilapper was unable to create a database.  Race will not be saved", Toast.LENGTH_LONG).show();
			return -1;
		}
		if(strRaceName != null && strRaceName.length() > 0 && lapParams.IsValid(fP2P))
		{
			ContentValues content = new ContentValues();
			content.put("\"name\"", strRaceName);
			content.put("\"date\"", "");
			content.put("x1", lapParams.lnStart != null ? lapParams.lnStart.GetP1().GetX() : 0);
			content.put("y1", lapParams.lnStart != null ? lapParams.lnStart.GetP1().GetY() : 0);
			content.put("x2", lapParams.lnStart != null ? lapParams.lnStart.GetP2().GetX() : 0);
			content.put("y2", lapParams.lnStart != null ? lapParams.lnStart.GetP2().GetY() : 0);
			
			content.put("x3", lapParams.lnStop != null ? lapParams.lnStop.GetP1().GetX() : 0);
			content.put("y3", lapParams.lnStop != null ? lapParams.lnStop.GetP1().GetY() : 0);
			content.put("x4", lapParams.lnStop != null ? lapParams.lnStop.GetP2().GetX() : 0);
			content.put("y4", lapParams.lnStop != null ? lapParams.lnStop.GetP2().GetY() : 0);
			
			content.put("x5", 0);
			content.put("y5", 0);
			content.put("x6", 0);
			content.put("y6", 0);
			
			content.put("vx1", lapParams.vStart != null ? lapParams.vStart.GetX() : 0);
			content.put("vy1", lapParams.vStart != null ? lapParams.vStart.GetY() : 0);
			
			content.put("vx2", lapParams.vStop != null ? lapParams.vStop.GetX() : 0);
			content.put("vy2", lapParams.vStop != null ? lapParams.vStop.GetY() : 0);
			
			content.put("vx3", 0);
			content.put("vy3", 0);
			
			
			content.put("\"testmode\"", fTestMode);
			content.put("p2p", fP2P ? 1 : 0);
			content.put("finishcount", iFinishCount);
			
			try
			{
				long id = db.insertOrThrow("races", null, content);
				return id;
			}
			catch(SQLiteException e)
			{
				return -1;
			}
		}
		return -1;
	}
	
	public static Bitmap GetBitmapFromDatabase(SQLiteDatabase db, long lId, int width, int height)
	{
		Cursor cur = db.rawQuery("select image from tracks where _id = " + String.valueOf(lId), null);
		if(cur != null)
		{
			while( cur.moveToFirst() ) 
			{
				byte[] asBytes = Base64.decode(cur.getBlob(0),Base64.DEFAULT);
				
				if( bImageMode ) 
				{
					Bitmap image = BitmapFactory.decodeByteArray(asBytes, 0, asBytes.length);
					return image;
				}
				else
				{
					RaceData rd = GetTrackData(Get(), lId, -1);
					LapAccumulatorParams param = rd.lapParams;
					ByteArrayInputStream bin = new ByteArrayInputStream(asBytes);
					DataInputStream din = new DataInputStream(bin);
					LapAccumulator lap = null;
					try {
						lap = new LapAccumulator(param,new Point2D(din.readFloat(),din.readFloat()),0);
						for (int i = 0; i < (asBytes.length-8)/8; i++) {
							lap.AddPosition(new Point2D(din.readFloat(),din.readFloat()), 0, 0);
						}
						din.close();
					}
					catch (IOException e){
						e.printStackTrace();
					}
					// We now have the points of the lap
					Bitmap bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
					if(bmp != null)
					{
						Canvas canvas = new Canvas(bmp);
						//lap.DoDeferredLoad(null, 0, false);

						Paint paintBlack = new Paint();
						paintBlack.setARGB(0,20,20,20);
						canvas.drawRect(0,0,width,height,paintBlack);

						FloatRect rcInWorld = lap.GetBounds(false);

						Paint paintLap = new Paint();
						paintLap.setARGB(255, 25, 140, 225);
						Paint paintSplits = new Paint();
						paintSplits.setColor(Color.RED);
						//paintLap.setStrokeCap(Cap.ROUND);
						paintLap.setAntiAlias(true);
//						paintLap.setStyle(Style.FILL_AND_STROKE);
						Rect rcOnScreen;
						 int iStrokeWidth = (int)Math.round(Math.min(width, height)/80);
//						 iStrokeWidth = 0;
						paintLap.setStrokeWidth(iStrokeWidth);
						paintSplits.setStrokeWidth(iStrokeWidth);

						rcOnScreen = new Rect(0,0,width,height);
						rcOnScreen.inset(iStrokeWidth,iStrokeWidth);
						
						LapAccumulator.DrawLap(lap, false, rcInWorld, canvas, paintLap, paintSplits, rcOnScreen);
					}
					return bmp;
				}
			}
		}
		return null;
	}

	public synchronized static long CreateTrackIfNotExist(SQLiteDatabase db, String strTrackName, LapAccumulator.LapAccumulatorParams lapParams, boolean fTestMode, boolean fP2P, int iFinishCount, int iRaceId)
	{
		if(db == null)
		{
			Toast.makeText(null, "Wifilapper was unable to create a database.  Track will not be saved", Toast.LENGTH_LONG).show();
			return -1;
		}
		

		
		if(strTrackName != null && strTrackName.length() > 0 && lapParams.IsValid(fP2P))
		{
			// blow away any tracks with the same name
			db.execSQL("delete from tracks where \"name\" = " + "\"" + strTrackName + "\"");

			ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
			if( bImageMode ) 
			{
				Bitmap bitTrackImage = GetRaceOutlineImage(db,iRaceId,400,400);
				bitTrackImage.compress(CompressFormat.PNG, 0, outputStream);
			}
			else {
				LapAccumulator bestLap = GetBestLap(db, lapParams, iRaceId );
				bestLap.DoDeferredLoad(null, 0, false);
				DataOutputStream dout = new DataOutputStream(outputStream);
				try {
					for (TimePoint2D pt : bestLap.GetPoints() ) {
							dout.writeFloat(pt.pt.GetX());
							dout.writeFloat(pt.pt.GetY());
					}
					dout.close();
				}
				catch (IOException e) {
					e.printStackTrace();
				}
			}
			ContentValues content = new ContentValues();
			content.put("\"name\"", strTrackName);
			content.put("\"date\"", "");
			content.put("x1", lapParams.lnStart != null ? lapParams.lnStart.GetP1().GetX() : 0);
			content.put("y1", lapParams.lnStart != null ? lapParams.lnStart.GetP1().GetY() : 0);
			content.put("x2", lapParams.lnStart != null ? lapParams.lnStart.GetP2().GetX() : 0);
			content.put("y2", lapParams.lnStart != null ? lapParams.lnStart.GetP2().GetY() : 0);
			
			content.put("x3", lapParams.lnStop != null ? lapParams.lnStop.GetP1().GetX() : 0);
			content.put("y3", lapParams.lnStop != null ? lapParams.lnStop.GetP1().GetY() : 0);
			content.put("x4", lapParams.lnStop != null ? lapParams.lnStop.GetP2().GetX() : 0);
			content.put("y4", lapParams.lnStop != null ? lapParams.lnStop.GetP2().GetY() : 0);
			
			content.put("x5", 0);
			content.put("y5", 0);
			content.put("x6", 0);
			content.put("y6", 0);
			
			content.put("vx1", lapParams.vStart != null ? lapParams.vStart.GetX() : 0);
			content.put("vy1", lapParams.vStart != null ? lapParams.vStart.GetY() : 0);
			
			content.put("vx2", lapParams.vStop != null ? lapParams.vStop.GetX() : 0);
			content.put("vy2", lapParams.vStop != null ? lapParams.vStop.GetY() : 0);
			
			content.put("vx3", 0);
			content.put("vy3", 0);
			
			
			content.put("\"testmode\"", fTestMode);
			content.put("p2p", fP2P ? 1 : 0);
			content.put("finishcount", iFinishCount);

			byte[] encodedBytes = Base64.encode(outputStream.toByteArray(),Base64.DEFAULT);
			content.put("image", encodedBytes);
//			content.put("image", outputStream.toByteArray());

			try
			{
				long id = db.insertOrThrow("tracks", null, content);
				return id;
			}
			catch(SQLiteException e)
			{
				return -1;
			}
		}
		return -1;
	}
	public static class RaceData
	{
		public LapAccumulator.LapAccumulatorParams lapParams;
		public String strRaceName;
		public boolean fTestMode;
		public long unixTimeMsStart;
		public long unixTimeMsEnd;
		public RaceData()
		{
			lapParams = new LapAccumulator.LapAccumulatorParams();
		}
	}
	public static class LapData
	{
		public LapData()
		{
			flLapTime = 0;
			lStartTime = 0;
			lLapId = 0;
			lRaceId = 0;
		}
		public LapData(LapData src)
		{
			flLapTime = src.flLapTime;
			lStartTime = src.lStartTime;
			lLapId = src.lLapId;
			lRaceId = src.lRaceId;
		}
		public float flLapTime;
		public long lStartTime; // in seconds since 1970
		public long lLapId;
		public long lRaceId;
	}
	public synchronized static RaceData GetTrackData(SQLiteDatabase db, long id, int iCarNumber)
	{
		Cursor cur = db.rawQuery("select x1,y1,x2,y2,x3,y3,x4,y4,x5,y5,x6,y6,vx1,vy1,vx2,vy2,vx3,vy3,name,testmode,finishcount from tracks where _id = " + id, null);
		if(cur.moveToFirst())
		{
//			cur.moveToFirst();
			RaceData ret = new RaceData();
			ret.strRaceName = cur.getString(18);
			ret.fTestMode = cur.getInt(19) != 0;
			final int iFinishCount = cur.getInt(20);
			float rgSF[] = new float[12];
			float rgSFDir[] = new float [6];
			for(int x = 0; x < 12; x++)
			{
				rgSF[x] = (float)cur.getDouble(x);
			}
			for(int x = 0; x < 6; x++)
			{
				rgSFDir[x] = (float)cur.getDouble(x+12);
			}
			ret.lapParams.InitFromRaw(rgSF,rgSFDir, iCarNumber, iFinishCount);
			
			cur.close();
			
			cur = db.rawQuery("select min(laps.unixtime) as starttime,max(laps.unixtime) as endtime from laps where raceid = " + id,null);
			if(cur != null)
			{
				cur.moveToFirst();
				ret.unixTimeMsStart = cur.getLong(0)*1000;
				ret.unixTimeMsEnd = cur.getLong(1)*1000;
				cur.close();
			}
			return ret;
		}
		return null;
	}
	public synchronized static RaceData GetRaceData(SQLiteDatabase db, long id, int iCarNumber)
	{
		Cursor cur = db.rawQuery("select x1,y1,x2,y2,x3,y3,x4,y4,x5,y5,x6,y6,vx1,vy1,vx2,vy2,vx3,vy3,name,testmode,finishcount from races where _id = " + id, null);
		if(cur != null)
		{
			cur.moveToFirst();
			RaceData ret = new RaceData();
			ret.strRaceName = cur.getString(18);
			ret.fTestMode = cur.getInt(19) != 0;
			final int iFinishCount = cur.getInt(20);
			float rgSF[] = new float[12];
			float rgSFDir[] = new float [6];
			for(int x = 0; x < 12; x++)
			{
				rgSF[x] = (float)cur.getDouble(x);
			}
			for(int x = 0; x < 6; x++)
			{
				rgSFDir[x] = (float)cur.getDouble(x+12);
			}
			ret.lapParams.InitFromRaw(rgSF,rgSFDir, iCarNumber, iFinishCount);
			
			cur.close();
			
			cur = db.rawQuery("select min(laps.unixtime) as starttime,max(laps.unixtime) as endtime from laps where raceid = " + id,null);
			if(cur != null)
			{
				cur.moveToFirst();
				ret.unixTimeMsStart = cur.getLong(0)*1000;
				ret.unixTimeMsEnd = cur.getLong(1)*1000;
				cur.close();
			}
			return ret;
		}
		return null;
	}
	private static void DoOrphanCheck(SQLiteDatabase db)
	{
		db.execSQL("create temporary table if not exists tempraceid (raceid integer);");
		db.execSQL("create temporary table if not exists templapid (lapid integer);");
		db.execSQL("create temporary table if not exists tempdataid (dataid integer);");
		db.execSQL("delete from tempraceid;");
		db.execSQL("delete from templapid;");
		db.execSQL("delete from tempdataid;");
		db.execSQL("insert into templapid select _id from laps where raceid not in (select _id from races);"); // finds all orphaned laps
		db.execSQL("insert into tempdataid select _id from channels where lapid in (select lapid from templapid);"); // finds all channels that depend on those orphaned laps
		db.execSQL("insert into tempdataid select _id from channels where lapid not in (select _id from laps);"); // finds all channels that depend on missing laps

		db.execSQL("delete from data where channelid in (select dataid from tempdataid);"); // deletes all data depending on the orphaned channels
		db.execSQL("delete from points where lapid in (select lapid from templapid);"); // deletes all points that depend on the targeted laps
		db.execSQL("delete from channels where _id in (select dataid from tempdataid);"); // deletes all channels that need deleting
		db.execSQL("delete from extras where _id in (select dataid from tempdataid);"); // deletes all channels that need deleting
		db.execSQL("delete from laps where _id in (select lapid from templapid);"); // deletes all targeted laps
		
		db.execSQL("delete from points where lapid not in (select _id from laps);"); // deletes all points that depend on nonexistent laps
		db.execSQL("delete from data where channelid not in (select _id from channels);"); // deletes all data that depend on nonexistent channels
	}
	public synchronized static void DeleteAllTracks(SQLiteDatabase db)
	{
		if(db == null) return;

		db.execSQL(	"drop table if exists tracks;");
		db.execSQL(CREATE_TRACK_SQL);
	}
	public synchronized static void DeleteTestData(SQLiteDatabase db)
	{
		if(db == null) return;

		db.execSQL("begin transaction;");
		db.execSQL(	"create table if not exists tempraceid (raceid integer);");
		db.execSQL(	"create table if not exists templapid (lapid integer);");
		db.execSQL(	"create table if not exists tempdataid (dataid integer);");	
		db.execSQL(	"delete from tempraceid;");
		db.execSQL(	"delete from templapid;");
		db.execSQL(	"delete from tempdataid;");
		db.execSQL(	"insert into tempraceid select _id from races where testmode=1;"); // finds all the races we want to delete
		db.execSQL(	"insert into templapid select _id from laps where raceid in (select raceid from tempraceid);"); // finds all the laps that depend on those races
		db.execSQL(	"insert into tempdataid select _id from channels where lapid in (select lapid from templapid);"); // finds all the channels that depend on those laps
		
		db.execSQL("delete from data where channelid in (select dataid from tempdataid);"); // deletes all raw data that depends on those channels
		db.execSQL("delete from points where lapid in (select lapid from templapid);"); // deletes all the points that depend on the doomed laps
		db.execSQL("delete from channels where lapid in (select lapid from templapid);"); // deletes all the channels that depend on the doomed laps
		db.execSQL("delete from extras where lapid in (select lapid from templapid);"); // deletes all the channels that depend on the doomed laps
		db.execSQL("delete from laps where raceid in (select raceid from tempraceid);"); // deletes all the laps that depend on the doomed races
		db.execSQL("delete from races where _id in (select raceid from tempraceid);"); // deletes all the doomed races
		db.execSQL("commit transaction;");
		
		DoOrphanCheck(db);
	}
	public synchronized static LapAccumulator GetBestLap(SQLiteDatabase db, LapAccumulator.LapAccumulatorParams lapParams, long lRaceId)
	{
		String strSQL;
		strSQL = "select _id, laptime, unixtime, raceid, min(laps.laptime) from laps where raceid = " + lRaceId;
		Cursor curLap = db.rawQuery(strSQL, null);
		if(curLap != null)
		{
			int cLaps = curLap.getCount();
			System.out.println("There are " + cLaps + " laps");
			while(curLap.moveToNext())
			{
				double dLapTime = curLap.getDouble(4);
				strSQL = "select _id, laptime, unixtime, raceid, min(laps.laptime) from laps where raceid = " + lRaceId + " and laptime = " + dLapTime;
				Cursor curBestLap = db.rawQuery(strSQL, null);
				if(curBestLap != null)
				{
					while(curBestLap.moveToNext())
					{
						int lLapDbId = curBestLap.getInt(0);
						int iUnixTime = curBestLap.getInt(2);
						LapAccumulator lap = new LapAccumulator(lapParams, iUnixTime, lLapDbId);
						curBestLap.close();
						curLap.close();
						return lap;
					}
				}
			}
			curLap.close();
		}
		return null;
	}
	public synchronized static LapAccumulator GetLap(SQLiteDatabase db, LapAccumulator.LapAccumulatorParams lapParams, long lLapId)
	{
		String strSQL = "select _id, laptime, unixtime, raceid, min(laps.laptime) from laps where _id = " + lLapId;
		Cursor curBestLap = db.rawQuery(strSQL, null);
		if(curBestLap != null)
		{
			while(curBestLap.moveToNext())
			{
				int lLapDbId = curBestLap.getInt(0);
				int iUnixTime = curBestLap.getInt(2);
				LapAccumulator lap = new LapAccumulator(lapParams, iUnixTime, lLapDbId);
				curBestLap.close();
				return lap;
			}
		}
		return null;
	}
	public synchronized static LapData[] GetLapDataList(SQLiteDatabase db, long lRaceId)
	{
		String strSQL;
		strSQL = "select _id, laptime, unixtime from laps where raceid = " + lRaceId;
		
		Cursor curLap = db.rawQuery(strSQL, null);
		
		LapData[] lst = new LapData[curLap.getCount()];
		int ix = 0;
		while(curLap.moveToNext())
		{
			LapData lap = new LapData();
			lap.flLapTime = (float)curLap.getDouble(1);
			lap.lLapId = curLap.getInt(0);
			lap.lStartTime = curLap.getLong(2);
			lap.lRaceId = lRaceId;
			lst[ix] = lap;
			ix++;
		}
		curLap.close();
		return lst;
	}
	public synchronized static Vector<LapAccumulator> GetLaps(SQLiteDatabase db, LapAccumulator.LapAccumulatorParams lapParams, long id)
	{
		Vector<LapAccumulator> lstRet = new Vector<LapAccumulator>();
		String strSQL;
		strSQL = "select _id, laptime, unixtime, raceid from laps where raceid = " + id;
		Cursor curLap = db.rawQuery(strSQL, null);
		if(curLap != null)
		{
			int cLaps = curLap.getCount();
			System.out.println("There are " + cLaps + " laps");
			while(curLap.moveToNext())
			{
				int lLapDbId = curLap.getInt(0);
				int iUnixTime = curLap.getInt(2);
				LapAccumulator lap = new LapAccumulator(lapParams, iUnixTime, lLapDbId);
				lstRet.add(lap);
			}
			curLap.close();
		}
		return lstRet;
	}
	public synchronized static void RenameRace(SQLiteDatabase db, int id, String strNewName)
	{
		try
		{
			db.beginTransaction();
			db.execSQL("update races set name = '" + strNewName + "' where _id = '" + id + "'");
			db.setTransactionSuccessful();
		}
		catch(SQLiteException e)
		{
			
		}
		db.endTransaction();
	}
	public synchronized static void RenameTrack(SQLiteDatabase db, int id, String strNewName)
	{
		try
		{
			db.beginTransaction();
			db.execSQL("update tracks set name = '" + strNewName + "' where _id = '" + id + "'");
			db.setTransactionSuccessful();
		}
		catch(SQLiteException e)
		{
			
		}
		db.endTransaction();
	}
	public synchronized static void DeleteRace(SQLiteDatabase db, int id)
	{
		try
		{
			db.beginTransaction();
			db.execSQL("delete from races where _id = '" + id + "'");
			db.execSQL("delete from laps where raceid = '" + id + "'");
			db.setTransactionSuccessful();
			
			RaceDatabase.DoOrphanCheck(db);
		}
		catch(SQLiteException e)
		{
			
		}
		db.endTransaction();
	}
	public synchronized static void DeleteTrack(SQLiteDatabase db, int id)
	{
		try
		{
			db.beginTransaction();
			db.execSQL("delete from tracks where _id = '" + id + "'");
			db.setTransactionSuccessful();
		}
		catch(SQLiteException e)
		{
			
		}
		db.endTransaction();
	}
	public synchronized static Bitmap GetRaceOutlineImage(SQLiteDatabase db, long lRaceId, int width, int height)
	{
		RaceData raceData = GetRaceData(db, lRaceId, -1);
		if(raceData != null)
		{
			LapAccumulator.LapAccumulatorParams lapParams = raceData.lapParams;
			
			LapAccumulator lap = GetBestLap(db, lapParams, lRaceId);
			if(lap != null)
			{
				Bitmap bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
				if(bmp != null)
				{
					Canvas canvas = new Canvas(bmp);
					lap.DoDeferredLoad(null, 0, false);
					
					Paint paintBlack = new Paint();
					paintBlack.setARGB(255,0,0,0);
					canvas.drawRect(0,0,canvas.getWidth(),canvas.getHeight(),paintBlack);
					
					FloatRect rcInWorld = lap.GetBounds(false);
					
					Paint paintLap = new Paint();
					paintLap.setARGB(255, 25, 140, 225);
					Paint paintSplits = new Paint();
					paintSplits.setColor(Color.RED);

					final float fStrokeWidth = Math.min(width, height)/200;
					paintLap.setStrokeWidth(fStrokeWidth);
					paintSplits.setStrokeWidth(fStrokeWidth);
					paintLap.setAntiAlias(true);

					Rect rcOnScreen = new Rect(0,0,width,height);
					LapAccumulator.DrawLap(lap, false, rcInWorld, canvas, paintLap, paintSplits, rcOnScreen);
				}
				return bmp;
			}
		}
		return null;
	}

	public synchronized static RaceData GetClosestTrack(List <RaceData> rd, double longitude, double latitude)
	{
		RaceData rdTmp;
		float rgDistance[] = new float[1];
		int iMinIndex = -1;
		float fMinDistance = 1e30f;

		if( rd==null || rd.size() <= 0 )
			return null;
		
		for(int ix = 0; ix < rd.size(); ix++)
		{
			rdTmp = rd.get(ix);
	    	Location.distanceBetween(latitude, longitude, rdTmp.lapParams.lnStart.GetP1().GetY(), rdTmp.lapParams.lnStart.GetP1().GetX(), rgDistance);
	    	if( rgDistance[0] < fMinDistance ) {
	    		iMinIndex = ix;
	    		fMinDistance = rgDistance[0];
	    	}
		}
		if( iMinIndex >= 0 )
			return rd.get(iMinIndex);
		else
			return null;
	}

	public synchronized static ArrayList<RaceData> GetTrackData(SQLiteDatabase db)
	{
		Cursor cur = db.rawQuery("select x1,y1,x2,y2,x3,y3,x4,y4,x5,y5,x6,y6,vx1,vy1,vx2,vy2,vx3,vy3,name,testmode,finishcount,_id from tracks", null);
		ArrayList<RaceData> ret = new ArrayList<RaceData>();
		if(cur != null)
		{
			int iFinishCount;
			float rgSF[] = new float[12];
			float rgSFDir[] = new float [6];
			if( !cur.moveToFirst() )
				return null;
			do {
				RaceData tmp = new RaceData();

				iFinishCount = cur.getInt(20);

				for(int x = 0; x < 12; x++)
				{
					rgSF[x] = (float)cur.getDouble(x);
				}
				for(int x = 0; x < 6; x++)
				{
					rgSFDir[x] = (float)cur.getDouble(x+12);
				}

				tmp.lapParams.InitFromRaw(rgSF, rgSFDir, 0, iFinishCount);
				tmp.strRaceName = cur.getString(18);
				tmp.unixTimeMsStart = cur.getLong(21); // DB ID

				ret.add(tmp);
			} while( cur.moveToNext() );
			cur.close();
		}
		return ret;
	}


	public synchronized static Cursor GetRaceList(SQLiteDatabase db)
	{
		try
		{
			Cursor cur = db.rawQuery("select races.finishcount as " + KEY_FINISHCOUNT + ", races.p2p as " + KEY_P2P + ", races._id as raceid,min(laps.laptime) as " + KEY_LAPTIME + ", races.name as name,count(laps._id) as lapcount, min(laps.unixtime) as " + KEY_STARTTIME + " from races left join laps on races._id = laps.raceid group by races._id order by races._id", null);

			return cur;
		}
		catch(SQLiteException e)
		{
			Log.w("sqldb",e.toString());
			e.printStackTrace();
		}
		
		return null;
	}
	
	public synchronized static Cursor GetTrackList(SQLiteDatabase db)
	{
		try
		{
			Cursor cur = db.rawQuery("select _id,name from tracks", null);

			return cur;
		}
		catch(SQLiteException e)
		{
			Log.w("sqldb",e.toString());
			e.printStackTrace();
		}
		
		return null;
	}
	
	public final static String CREATE_TRACK_SQL =  "create table if not exists tracks (	" + 
			"_id integer primary key asc autoincrement, " +
			"\"name\" string, " +
			"\"date\" string, " +
			"\"testmode\" integer, " +
			"x1 real, " +
			"y1 real, " +
			"x2 real, " +
			"y2 real, " +
			"x3 real, " +
			"y3 real, " +
			"x4 real, " +
			"y4 real, " +
			"x5 real, " +
			"y5 real, " +
			"x6 real, " +
			"y6 real, " +
			"vx1 real," +
			"vy1 real," +
			"vx2 real," +
			"vy2 real," +
			"vx3 real," +
			"vy3 real," +
			"p2p integer not null default 0," +
			"finishcount integer not null default 1," +
			"image BLOB)";

	private final static String CREATE_RACE_SQL =  "create table if not exists races (	_id integer primary key asc autoincrement, " +
																						"\"name\" string, " +
																						"\"date\" string, " +
																						"\"testmode\" integer, " +
																						"x1 real, " +
																						"y1 real, " +
																						"x2 real, " +
																						"y2 real, " +
																						"x3 real, " +
																						"y3 real, " +
																						"x4 real, " +
																						"y4 real, " +
																						"x5 real, " +
																						"y5 real, " +
																						"x6 real, " +
																						"y6 real, " +
																						"vx1 real," +
																						"vy1 real," +
																						"vx2 real," +
																						"vy2 real," +
																						"vx3 real," +
																						"vy3 real," +
																						"p2p integer not null default 0," +
																						"finishcount integer not null default 1)";
	
	private final static String CREATE_LAPS_SQL = "create table if not exists laps " +
													"(_id integer primary key asc autoincrement, " +
													"laptime real, " + 
													"unixtime integer, " +
													"transmitted integer, " +
													"raceid integer," +
													"foreign key (raceid) references races(_id));";
	
	private final static String CREATE_POINTS_SQL = "create table if not exists points " +
													"(_id integer primary key asc autoincrement, " +
													"x real," +
													"y real," +
													"time integer," +
													"velocity real," +
													"lapid integer," +
													"foreign key (lapid) references laps(_id));";
	
	private final static String CREATE_CHANNELS_SQL = "create table if not exists channels" +
													  "(_id integer primary key asc autoincrement, " +
													  "lapid integer NOT NULL," +
													  "channeltype integer NOT NULL," +
													  "foreign key(lapid) references laps(_id));";
	
	private final static String CREATE_DATA_SQL = "create table if not exists data " +
														 "(_id integer primary key asc autoincrement," +
														 "time integer NOT NULL," +
														 "value real NOT NULL," +
														 "channelid integer NOT NULL," +
														 "foreign key (channelid) references channels(_id));";
	
	private final static String CREATE_EXTRA_SQL =	"create table extras " +
													"(_id integer primary key asc autoincrement," +
													"comment string," +
													"lapid integer NOT NULL unique on conflict fail," +
													"foreign key (lapid) references laps(_id))";
	
	private final static String CREATE_INDICES ="create index if not exists data_channelid on data(channelid);" +
												"create index if not exists points_lapid on points(lapid);" +
												"create index if not exists laps_raceid on laps(raceid);";
	@Override
	public void onCreate(SQLiteDatabase db)
	{
		db.execSQL(CREATE_TRACK_SQL);
		db.execSQL(CREATE_RACE_SQL);
		db.execSQL(CREATE_LAPS_SQL);
		db.execSQL(CREATE_POINTS_SQL);
		db.execSQL(CREATE_CHANNELS_SQL);
		db.execSQL(CREATE_DATA_SQL);
		db.execSQL(CREATE_INDICES);
		db.execSQL(CREATE_EXTRA_SQL);
	}
	private void ExecAndIgnoreException(SQLiteDatabase db, String strSQL)
	{
		try
		{
			db.execSQL(strSQL);
		}
		catch(SQLiteException e)
		{
			
		}
	}
	@Override
	public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion)
	{
//		ExecAndIgnoreException(db,"drop table if exists tracks"); // remove this later
//		db.execSQL(CREATE_TRACK_SQL); // add the "tracks" table

		if(oldVersion <= 18)
		{
			ExecAndIgnoreException(db,"drop table races");
			ExecAndIgnoreException(db,"drop table laps");
			ExecAndIgnoreException(db,"drop table points");
			ExecAndIgnoreException(db,"drop table channels");
			ExecAndIgnoreException(db,"drop table data");
			ExecAndIgnoreException(db,"drop table extras");
			onCreate(db);
		}
		else
		{
			if(oldVersion == 19 && newVersion > 19)
			{
				// 19->20: added indices for more speed
				db.execSQL(CREATE_INDICES);
				oldVersion = 20;
			}
			if(oldVersion == 20 && newVersion > 20)
			{
				// upgrade more...
				db.execSQL("alter table races add column p2p integer not null default 0");
				oldVersion = 21;
			}
			if(oldVersion == 21 && newVersion > 21)
			{
				// upgrade more...
				db.execSQL("alter table races add column finishcount integer not null default 1");
				oldVersion = 22;
			}
			if(oldVersion == 22 && newVersion > 22)
			{
				db.execSQL(CREATE_EXTRA_SQL); // add the "extras" table
				oldVersion = 23;
			}
			if(oldVersion == 23 && newVersion > 23)
			{

				db.execSQL(CREATE_TRACK_SQL); // add the "tracks" table
				oldVersion = 24;
			}
		}
	}
}

