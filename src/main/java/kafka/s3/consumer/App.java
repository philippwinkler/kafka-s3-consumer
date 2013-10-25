package kafka.s3.consumer;

import static java.util.concurrent.TimeUnit.SECONDS;

import java.io.IOException;
import java.net.URL;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Observable;
import java.util.Observer;
import java.util.Properties;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;

import kafka.consumer.ConsumerConfig;
import kafka.consumer.ConsumerIterator;
import kafka.consumer.KafkaStream;
import kafka.javaapi.consumer.ConsumerConnector;
import kafka.message.Message;
import kafka.message.MessageAndMetadata;

import org.apache.commons.configuration.ConfigurationException;
import org.slf4j.LoggerFactory;

public class App {

	private static final org.slf4j.Logger logger = LoggerFactory
			.getLogger(App.class);
	private static PropertyConfiguration conf;
	private static ExecutorService pool;
	private static ScheduledExecutorService scheduler;

	private static Runnable doPoolStatusCheck(
			final List<ArchivingWorker> workers) {
		return new Runnable() {
			public void run() {
				for (ArchivingWorker worker : workers) {
					logger.info(worker.toString());
					worker.submitStats();
				}
			}
		};
	}

	public static void main(String[] args) {
		conf = loadConfiguration(args);

		Map<String, Integer> topics = conf.getTopicsAndPartitions();
		final List<ArchivingWorker> workers = new LinkedList<ArchivingWorker>();
		Integer workerCount = 0;

		for (Map.Entry<String, Integer> entry : topics.entrySet()) {
			workerCount += entry.getValue();
		}
		pool = Executors.newFixedThreadPool(workerCount);
		scheduler = Executors.newScheduledThreadPool(1);

		for (Map.Entry<String, Integer> entry : topics.entrySet()) {
			for (int partition = 0; partition < entry.getValue(); partition++) {
				workers.add(new ArchivingWorker(entry.getKey(), partition,
						conf, pool));
			}
		}

		assert (workerCount == workers.size());
		logger.info("Starting workers to archive into {}/{}",
				conf.getS3Bucket(), conf.getS3Prefix());
		for (ArchivingWorker worker : workers) {
			logger.info(String.format("  %s", worker));
			pool.execute(worker);
		}

		@SuppressWarnings("unused")
		ScheduledFuture<?> statsScheduler = scheduler.scheduleWithFixedDelay(
				doPoolStatusCheck(workers), 0, 30, SECONDS);

	}

	private static class ArchivingWorker implements Runnable, Observer {

		private final String topic;
		private final int partition;
		private PropertyConfiguration masterConfig;

		private final ConsumerConnector consumer;
		private long messageCount = 0;
		private long totalMessageSize = 0;
		Map<String, List<KafkaStream<byte[], byte[]>>> consumerMap;

		private ArchivingWorker(String topic, int partition,
				PropertyConfiguration masterConfig, ExecutorService pool) {
			this.topic = topic;
			this.partition = partition;
			this.masterConfig = masterConfig;
			consumer = kafka.consumer.Consumer
					.createJavaConsumerConnector(createConsumerConfig(topic,
							masterConfig));
		}

		private static ConsumerConfig createConsumerConfig(String topic,
				PropertyConfiguration conf) {
			Properties props = new Properties();
			String fetchSize;			

			logger.debug("Zookeeper connect string {}",
					conf.getString(PropertyConfiguration.ZK_CONNECT_STRING));
			props.put("zookeeper.connect",
					conf.getString(PropertyConfiguration.ZK_CONNECT_STRING));
			props.put("group.id",
					conf.getString(PropertyConfiguration.CONSUMER_GROUP_ID));
			props.put("zookeeper.sessiontimeout.ms",
					conf.getString(PropertyConfiguration.ZK_SESSION_TIMEOUT));
			props.put("zookeeper.synctime.ms", 
					conf.getString(PropertyConfiguration.ZK_SYNC_TIME));
			props.put("auto.commit.enable", "false");

			fetchSize = conf.getString(PropertyConfiguration.DEFAULT_FETCH_SIZE
					+ "." + topic,
					conf.getString(PropertyConfiguration.DEFAULT_FETCH_SIZE));
			logger.info("Fetch size for topic {} set to {}", topic, fetchSize);
			props.put("fetch.size", fetchSize);

			props.put("socket.buffersize",
					conf.getString(PropertyConfiguration.SOCKET_BUFFER_SIZE));

			return new ConsumerConfig(props);
		}

		@Override
		public void run() {
			logger.warn("RUN'ning offload thread");
			Sink sink;
			try {
				sink = new S3JsonFileSink(topic, partition, conf);
				sink.addObserver(this);
				Map<String, Integer> topicCountMap = new HashMap<String, Integer>();
				topicCountMap.put(topic, 1);

				consumerMap = consumer.createMessageStreams(topicCountMap);
				if (consumerMap.containsKey(topic)
						&& consumerMap.get(topic).size() > 0) {
					KafkaStream<byte[], byte[]> stream = consumerMap.get(topic).get(0);
					ConsumerIterator<byte[], byte[]> it = stream.iterator();
					while (it.hasNext()) {
						MessageAndMetadata<byte[], byte[]> msgAndMetadata = it.next();
						totalMessageSize += sink.append(msgAndMetadata);
						messageCount += 1;
					}
				} else {
					logger.warn(
							"Topic {} not found in ConsumerMap / Kafka stream",
							topic);
				}
			} catch (Exception e) {

				logger.warn(
						"Critical error in Archiving worker for topic {}. Relaunching thread.",
						topic, e);
				pool.execute(new ArchivingWorker(topic, partition,
						masterConfig, pool));
			}
		}

		@Override
		public String toString() {
			return String
					.format("ArchivingWorker(topic=%s,partition=%d,messageCount=%d,totalMessageSize=%d)",
							topic, partition, messageCount, totalMessageSize);
		}

		public void submitStats() {
			// TODO: replace with your stats submission code
		}

		@Override
		public void update(Observable obs, Object arg) {
			if (consumerMap != null) {
				logger.info("Commiting offsets to zookeeper");
				consumer.commitOffsets();
			} else {
				logger.warn("Update called before queue connection fully initialized");
			}

		}
	}

	private static PropertyConfiguration loadConfiguration(String[] args) {
		URL propsURL;
		try {
			if (args == null || args.length != 1) {
				propsURL = App.class.getResource("/app.properties");

			} else {
				propsURL = new URL("file://" + args[0]);
			}
		} catch (IOException e) {
			throw new RuntimeException(e);
		}

		try {
			return new PropertyConfiguration(propsURL);
		} catch (ConfigurationException e) {
			logger.warn("Incorect config. Exiting", e);
			throw new RuntimeException(e);
		}
	}
}
