ALTER TABLE users
    ADD COLUMN is_verified BOOLEAN NOT NULL DEFAULT FALSE;


CREATE TABLE otp_verifications (
                                   id UUID PRIMARY KEY DEFAULT gen_random_uuid(),

                                   user_id UUID NOT NULL,

                                   otp_hash VARCHAR(255) NOT NULL,

                                   purpose VARCHAR(50) NOT NULL,

                                   expires_at TIMESTAMPTZ NOT NULL,

                                   attempts INTEGER NOT NULL DEFAULT 0,

                                   verified_at TIMESTAMPTZ,

                                   created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

                                   CONSTRAINT fk_otp_verification_user
                                       FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,

                                   CONSTRAINT chk_otp_purpose
                                       CHECK (
                                           purpose IN (
                                                       'PHONE_VERIFICATION',
                                                       'PASSWORD_RESET',
                                                       'PHONE_CHANGE'
                                               )
                                           ),

                                   CONSTRAINT chk_otp_attempts
                                       CHECK (attempts >= 0)
);


CREATE INDEX idx_otp_verifications_user_id
    ON otp_verifications(user_id);