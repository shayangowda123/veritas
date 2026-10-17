package com.veritas;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import javax.sql.DataSource;

@SpringBootTest
@TestPropertySource(properties = {
		"security.jwt.secret-base64=dGVzdC1zZWNyZXQta2V5LWZvci12ZXJpdGFzLXJlbnRhbC1hcHAtMjU2LWJpdC1taW5pbXVtIQ==",
		"security.jwt.expiration-ms=3600000",
		"spring.flyway.enabled=false",
		"spring.jpa.database-platform=org.hibernate.dialect.PostgreSQLDialect",
		"spring.jpa.hibernate.ddl-auto=none"
})
class VeritasApplicationTests {

	@MockitoBean(answers = org.mockito.Answers.RETURNS_DEEP_STUBS)
	private DataSource dataSource;

	@Test
	void contextLoads() {
	}

}
