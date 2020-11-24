CREATE TABLE IF NOT EXISTS history (
	id SERIAL PRIMARY KEY, 
	created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP	
);

CREATE TABLE IF NOT EXISTS locomotives (
	id SERIAL PRIMARY KEY, 
	history_id integer NOT NULL,
	scac character varying(255) NOT NULL,
	mark character varying(255) NOT NULL,
	locoID character varying(255) NOT NULL,
	ATTModem_Address character varying(15),
	ATTModem_Status boolean NOT NULL,
	VZWModem_Address character varying(15),
	VZWModem_Status boolean NOT NULL,
	WiFi_Address character varying(255),
	WiFi_Status boolean NOT NULL,
	Radio_Address character varying(255),
	Radio_Status boolean NOT null,
	CONSTRAINT history_link 
		FOREIGN KEY (history_id) 
		REFERENCES history(id) 
		ON DELETE RESTRICT
);

CREATE INDEX locomotive_history ON locomotives(history_id);

CREATE INDEX history_timestamp ON history(created_at);
