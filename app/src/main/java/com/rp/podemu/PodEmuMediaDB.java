/**

 Copyright (C) 2017, Roman P., dev.roman [at] gmail

 This program is free software; you can redistribute it and/or modify
 it under the terms of the GNU General Public License as published by
 the Free Software Foundation; either version 3 of the License, or
 (at your option) any later version.

 This program is distributed in the hope that it will be useful,
 but WITHOUT ANY WARRANTY; without even the implied warranty of
 MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 GNU General Public License for more details.

 You should have received a copy of the GNU General Public License
 along with this program. If not, see http://www.gnu.org/licenses/

 */
package com.rp.podemu;

/**
 * Created by rp on 10/31/15.
 */
public abstract class PodEmuMediaDB
        implements PodEmuMediaStore.DBLoadInterface
{
    private static PodEmuMediaDB instance=null;
    protected static String ctrlAppProcessName=null;

    public static void initialize(String app)
    {

        if(ctrlAppProcessName==null || !ctrlAppProcessName.equals(app) )
        {
            ctrlAppProcessName = app;
            instance = new PodEmuMediaDB_Generic();
        }
        else
        {
            PodEmuLog.error("PEMDB: sth went wrong - DB initialization without rebuilding DB");
        }

    }

    public static PodEmuMediaDB getInstance()
    {
        return instance;
    }



    public abstract void rebuildDB(PodEmuMediaStore mediaStore);

    public abstract void rebuildDB(PodEmuMediaStore mediaStore, int trackCount);

    /**
     * This function is only required for Generic DB. For others it should be empty.
     * @param mediaStore
     */
    public abstract void signalConfigurationUpdated(PodEmuMediaStore mediaStore);

}
