CREATE EXTENSION IF NOT EXISTS postgis;

CREATE TABLE users (
                       id UUID PRIMARY KEY DEFAULT gen_random_uuid(),

                       name VARCHAR(100) NOT NULL,

                       email VARCHAR(255) NOT NULL UNIQUE,

                       password_hash VARCHAR(255) NOT NULL,

                       phone VARCHAR(20),

                       created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

                       updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);


CREATE TABLE properties (
                            id UUID PRIMARY KEY DEFAULT gen_random_uuid(),

                            owner_id UUID NOT NULL,

                            property_type VARCHAR(50) NOT NULL,

                            address TEXT NOT NULL,

                            location GEOGRAPHY(POINT, 4326) NOT NULL,

                            created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

                            updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

                            CONSTRAINT fk_property_owner
                                FOREIGN KEY (owner_id)
                                    REFERENCES users(id)
);


CREATE TABLE listings (
                          id UUID PRIMARY KEY DEFAULT gen_random_uuid(),

                          property_id UUID NOT NULL,

                          rent NUMERIC(12, 2) NOT NULL,

                          deposit NUMERIC(12, 2) NOT NULL,

                          bhk INTEGER NOT NULL,

                          built_up_area NUMERIC(10, 2) NOT NULL,

                          floor INTEGER NOT NULL,

                          bachelors_allowed BOOLEAN NOT NULL DEFAULT FALSE,

                          available_from DATE NOT NULL,

                          description TEXT,

                          status VARCHAR(20) NOT NULL DEFAULT 'DRAFT',

                          created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

                          updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

                          CONSTRAINT fk_listing_property
                              FOREIGN KEY (property_id)
                                  REFERENCES properties(id),

                          CONSTRAINT chk_listing_rent
                              CHECK (rent > 0),

                          CONSTRAINT chk_listing_deposit
                              CHECK (deposit >= 0),

                          CONSTRAINT chk_listing_bhk
                              CHECK (bhk > 0),

                          CONSTRAINT chk_listing_area
                              CHECK (built_up_area > 0)
);


CREATE TABLE property_images (
                                 id UUID PRIMARY KEY DEFAULT gen_random_uuid(),

                                 property_id UUID NOT NULL,

                                 storage_key VARCHAR(500) NOT NULL,

                                 display_order INTEGER NOT NULL DEFAULT 0,

                                 created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

                                 CONSTRAINT fk_property_image_property
                                     FOREIGN KEY (property_id)
                                         REFERENCES properties(id)
                                         ON DELETE CASCADE,

                                 CONSTRAINT chk_property_image_order
                                     CHECK (display_order >= 0)
);


CREATE TABLE pois (
                      id UUID PRIMARY KEY DEFAULT gen_random_uuid(),

                      name VARCHAR(255) NOT NULL,

                      type VARCHAR(50) NOT NULL,

                      location GEOGRAPHY(POINT, 4326) NOT NULL,

                      created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);



CREATE TABLE conversations (
                               id UUID PRIMARY KEY DEFAULT gen_random_uuid(),

                               listing_id UUID NOT NULL,

                               owner_id UUID NOT NULL,

                               renter_id UUID NOT NULL,

                               created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

                               updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

                               CONSTRAINT fk_conversation_listing
                                   FOREIGN KEY (listing_id)
                                       REFERENCES listings(id),

                               CONSTRAINT fk_conversation_owner
                                   FOREIGN KEY (owner_id)
                                       REFERENCES users(id),

                               CONSTRAINT fk_conversation_renter
                                   FOREIGN KEY (renter_id)
                                       REFERENCES users(id),

                               CONSTRAINT chk_different_conversation_users
                                   CHECK (owner_id <> renter_id)
);

CREATE TABLE messages (
                          id UUID PRIMARY KEY DEFAULT gen_random_uuid(),

                          conversation_id UUID NOT NULL,

                          sender_id UUID NOT NULL,

                          content TEXT NOT NULL,

                          read_at TIMESTAMPTZ,

                          created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

                          CONSTRAINT fk_message_conversation
                              FOREIGN KEY (conversation_id)
                                  REFERENCES conversations(id)
                                  ON DELETE CASCADE,

                          CONSTRAINT fk_message_sender
                              FOREIGN KEY (sender_id)
                                  REFERENCES users(id),

                          CONSTRAINT chk_message_content
                              CHECK (length(trim(content)) > 0)
);



CREATE INDEX idx_properties_owner_id
    ON properties(owner_id);

CREATE INDEX idx_listings_property_id
    ON listings(property_id);

CREATE INDEX idx_listings_status
    ON listings(status);

CREATE INDEX idx_property_images_property_id
    ON property_images(property_id);

CREATE INDEX idx_conversations_listing_id
    ON conversations(listing_id);

CREATE INDEX idx_conversations_owner_id
    ON conversations(owner_id);

CREATE INDEX idx_conversations_renter_id
    ON conversations(renter_id);

CREATE INDEX idx_messages_conversation_id_created_at
    ON messages(conversation_id, created_at);

CREATE INDEX idx_properties_location
    ON properties USING GIST(location);

CREATE INDEX idx_pois_location
    ON pois USING GIST(location);