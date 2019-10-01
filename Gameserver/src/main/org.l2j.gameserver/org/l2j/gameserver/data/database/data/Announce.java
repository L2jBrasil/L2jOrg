package org.l2j.gameserver.data.database.data;

import org.l2j.gameserver.model.announce.AnnouncementType;

public interface Announce {

    boolean isValid();

    AnnouncementType getType();

    String getContent();

    boolean canBeStored();

    int getId();

    String getAuthor();

    void setType(AnnouncementType type);

    void setContent(String content);

    void setAuthor(String name);
}
