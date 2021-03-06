/* This file is part of VoltDB.
 * Copyright (C) 2008-2010 VoltDB L.L.C.
 *
 * VoltDB is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * VoltDB is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with VoltDB.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.voltdb.debugstate;

import java.io.Serializable;

public class MailboxHistory implements Serializable {
    private static final long serialVersionUID = 6033531946753318621L;

    public static class MessageState implements Serializable {
        private static final long serialVersionUID = 1318375589783882683L;

        public int fromSiteId;
        public int toSiteId;
        public long txnId;
        public String summary;
    }

    public int siteId;
    public MessageState[] messagesSent = null;
    public MessageState[] messagesReceived = null;
    public MessageState[] heartbeatsReceived = null;
    public MessageState[] noticesReceived = null;
}
