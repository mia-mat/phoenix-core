package ws.mia.phoenix.core.configuration;

import com.mongodb.ConnectionString;
import com.mongodb.MongoClientSettings;
import com.mongodb.reactivestreams.client.MongoClient;
import com.mongodb.reactivestreams.client.MongoClients;
import com.mongodb.reactivestreams.client.MongoCollection;
import com.mongodb.reactivestreams.client.MongoDatabase;
import org.bson.Document;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.TimeUnit;

@Configuration
public class MongoConfiguration {

	public static final String ROUTE_COLLECTION_NAME;
	static {
		String env = System.getenv("PHOENIX_ROUTE_COLLECTION_NAME");
		ROUTE_COLLECTION_NAME = env != null	? env : "routes";
	}

	@Bean
	public MongoClient mongoClient() {
		String uri = System.getenv("PHOENIX_MONGO_URI");
		if (uri == null || uri.isBlank()) {
			throw new IllegalStateException("PHOENIX_MONGO_URI environment variable must be set!");
		}

		ConnectionString connectionString = new ConnectionString(uri);
		MongoClientSettings settings = MongoClientSettings.builder()
				.applyConnectionString(connectionString)
				.applyToConnectionPoolSettings(builder -> builder
						.maxSize(100)
						.minSize(5)
						.maxWaitTime(5, TimeUnit.SECONDS))
				.build();

		return MongoClients.create(settings);
	}

	@Bean
	public String mongoDatabaseName() {
		String uri = System.getenv("PHOENIX_MONGO_URI");
		ConnectionString connectionString = new ConnectionString(uri);
		String db = connectionString.getDatabase();
		if (db == null) {
			throw new IllegalStateException("No database name specified in PHOENIX_MONGO_URI");
		}
		return db;
	}

	@Bean
	public MongoDatabase mongoDatabase(MongoClient mongoClient, String mongoDatabaseName) {
		return mongoClient.getDatabase(mongoDatabaseName);
	}

	@Bean
	public MongoCollection<Document> routesCollection(MongoDatabase database) {
		return database.getCollection(ROUTE_COLLECTION_NAME);
	}
}


