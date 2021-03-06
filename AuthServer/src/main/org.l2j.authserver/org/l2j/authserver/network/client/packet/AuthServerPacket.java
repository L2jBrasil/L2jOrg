/*
 * Copyright © 2019-2020 L2JOrg
 *
 * This file is part of the L2JOrg project.
 *
 * L2JOrg is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * L2JOrg is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package org.l2j.authserver.network.client.packet;

import io.github.joealisson.mmocore.WritablePacket;
import org.l2j.authserver.network.client.AuthClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * @author KenM
 */
public abstract class AuthServerPacket extends WritablePacket<AuthClient> {

    private static final Logger logger = LoggerFactory.getLogger(AuthServerPacket.class);

    @Override
    protected boolean write(AuthClient client) {
        try {
            writeImpl(client);
            return true;
        } catch (Exception e) {
           logger.error(e.getMessage(),e);
        }
        return false;
    }

    protected abstract void writeImpl(AuthClient client);
}
