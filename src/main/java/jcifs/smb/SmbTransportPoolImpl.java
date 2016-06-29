/*
 * © 2016 AgNO3 Gmbh & Co. KG
 * 
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 2.1 of the License, or (at your option) any later version.
 * 
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 * 
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA  02111-1307  USA
 */
package jcifs.smb;


import java.io.IOException;
import java.net.InetAddress;
import java.util.LinkedList;
import java.util.List;

import org.apache.log4j.Logger;

import jcifs.CIFSContext;
import jcifs.CIFSException;
import jcifs.SmbConstants;
import jcifs.SmbTransportPool;
import jcifs.netbios.UniAddress;


/**
 * @author mbechler
 *
 */
public class SmbTransportPoolImpl implements SmbTransportPool {

    private static final Logger log = Logger.getLogger(SmbTransportPool.class);

    private final List<SmbTransport> connections = new LinkedList<>();
    private final List<SmbTransport> nonPooledConnections = new LinkedList<>();


    @Override
    public SmbTransport getSmbTransport ( CIFSContext tc, UniAddress address, int port, boolean nonPooled ) {
        return getSmbTransport(tc, address, port, tc.getConfig().getLocalAddr(), tc.getConfig().getLocalPort(), null, nonPooled);
    }


    @Override
    public SmbTransport getSmbTransport ( CIFSContext tc, UniAddress address, int port, InetAddress localAddr, int localPort, String hostName,
            boolean nonPooled ) {
        if ( port <= 0 ) {
            port = SmbConstants.DEFAULT_PORT;
        }
        synchronized ( this.connections ) {
            SmbComNegotiate negotiate = new SmbComNegotiate(tc.getConfig());
            if ( nonPooled || tc.getConfig().getSessionLimit() != 1 ) {
                for ( SmbTransport conn : this.connections ) {
                    if ( conn.matches(address, port, localAddr, localPort, hostName)
                            && ( tc.getConfig().getSessionLimit() == 0 || conn.sessions.size() < tc.getConfig().getSessionLimit() ) ) {
                        /*
                         * Compare the flags2 field in SMB block to decide
                         * // whether the authentication method is changed. Because one
                         * // tranport can only negotiate only once, if authentication
                         * // method is changed, we need to re-create the transport to
                         * re-negotiate with server.
                         */
                        if ( conn.getNegotiateRequest().flags2 == negotiate.flags2 ) {
                            if ( log.isDebugEnabled() ) {
                                log.debug("Reusing transport connection " + conn);
                            }
                            return conn;
                        }
                    }
                }
            }
            SmbTransport conn = new SmbTransport(tc, negotiate, address, port, localAddr, localPort);
            if ( log.isDebugEnabled() ) {
                log.debug("New transport connection " + conn);
            }
            if ( nonPooled ) {
                this.nonPooledConnections.add(conn);
            }
            else {
                this.connections.add(0, conn);
            }
            return conn;
        }
    }


    @Override
    public void removeTransport ( SmbTransport trans ) {
        synchronized ( this.connections ) {
            if ( log.isDebugEnabled() ) {
                log.debug("Removing transport connection " + trans + " (" + System.identityHashCode(trans) + ")");
            }
            this.connections.remove(trans);
            this.nonPooledConnections.remove(trans);
        }
    }


    /**
     * {@inheritDoc}
     *
     * @see jcifs.SmbTransportPool#close()
     */
    @Override
    public void close () throws CIFSException {
        synchronized ( this.connections ) {
            log.debug("Closing pool");
            List<SmbTransport> toClose = new LinkedList<>(this.connections);
            toClose.addAll(this.nonPooledConnections);
            for ( SmbTransport conn : toClose ) {
                try {
                    conn.disconnect(false);
                }
                catch ( IOException e ) {
                    log.warn("Failed to close connection", e);
                }
            }
            this.connections.clear();
            this.nonPooledConnections.clear();
        }
    }


    @Override
    public byte[] getChallenge ( UniAddress dc, CIFSContext tf ) throws SmbException {
        return getChallenge(dc, 0, tf);
    }


    @Override
    public byte[] getChallenge ( UniAddress dc, int port, CIFSContext tf ) throws SmbException {
        SmbTransport trans = tf.getTransportPool().getSmbTransport(tf, dc, port, false);
        trans.connect();
        return trans.server.encryptionKey;
    }


    @Override
    public void logon ( UniAddress dc, CIFSContext tf ) throws SmbException {
        logon(dc, 0, tf);
    }


    @Override
    public void logon ( UniAddress dc, int port, CIFSContext tf ) throws SmbException {
        SmbTransport smbTransport = tf.getTransportPool().getSmbTransport(tf, dc, port, false);
        SmbSession smbSession = smbTransport.getSmbSession(tf);
        SmbTree tree = smbSession.getSmbTree(tf.getConfig().getLogonShare(), null);
        if ( tf.getConfig().getLogonShare() == null ) {
            tree.treeConnect(null, null);
        }
        else {
            Trans2FindFirst2 req = new Trans2FindFirst2(tree.session.getConfig(), "\\", "*", SmbFile.ATTR_DIRECTORY);
            Trans2FindFirst2Response resp = new Trans2FindFirst2Response(tree.session.getConfig());
            tree.send(req, resp);
        }
    }

}
