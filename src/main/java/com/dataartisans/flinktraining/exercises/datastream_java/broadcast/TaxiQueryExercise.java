/*
 * Copyright 2018 data Artisans GmbH
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.dataartisans.flinktraining.exercises.datastream_java.broadcast;

import com.dataartisans.flinktraining.exercises.datastream_java.datatypes.TaxiRide;
import com.dataartisans.flinktraining.exercises.datastream_java.sources.TaxiRideSource;
import com.dataartisans.flinktraining.exercises.datastream_java.utils.ExerciseBase;
import com.dataartisans.flinktraining.exercises.datastream_java.utils.MissingSolutionException;
import org.apache.flink.api.common.state.MapStateDescriptor;
import org.apache.flink.api.common.state.State;
import org.apache.flink.api.common.state.ValueState;
import org.apache.flink.api.common.state.ValueStateDescriptor;
import org.apache.flink.api.common.typeinfo.BasicTypeInfo;
import org.apache.flink.api.common.typeinfo.TypeInformation;
import org.apache.flink.api.java.tuple.Tuple2;
import org.apache.flink.api.java.utils.ParameterTool;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.runtime.state.KeyedStateFunction;
import org.apache.flink.streaming.api.TimeCharacteristic;
import org.apache.flink.streaming.api.datastream.BroadcastStream;
import org.apache.flink.streaming.api.datastream.DataStream;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.streaming.api.functions.AssignerWithPeriodicWatermarks;
import org.apache.flink.streaming.api.functions.co.KeyedBroadcastProcessFunction;
import org.apache.flink.streaming.api.functions.source.SocketTextStreamFunction;
import org.apache.flink.streaming.api.watermark.Watermark;
import org.apache.flink.util.Collector;
import org.codehaus.commons.compiler.CompileException;
import org.codehaus.janino.ExpressionEvaluator;
import org.joda.time.format.DateTimeFormat;
import org.joda.time.format.DateTimeFormatter;

import javax.annotation.Nullable;
import java.lang.reflect.InvocationTargetException;
import java.util.Locale;
import java.util.Map;

/**
 *
 * Parameters:
 * -input path-to-input-file
 *
 * Taxi Rides are streamed into keyed state (keyed by taxi ID), storing the latest ride event for taxi.
 *
 * Use nc -lk 9999 to establish a socket stream from stdin on port 9999
 *
 * On that socket stream, type java expressions to be matched against both the stored
 * state and newly arriving rides. These expressions have a "ride" and the current "watermark"
 * in scope.
 *
 * Examples:
 *
 *     true -- match everything: dump all stored state and match all incoming rides
 *     false -- match nothing: stop emitting any results
 *     ride.isStart && (watermark - ride.getEventTime()) > 100 * 60000  -- match ongoing rides that started more than 100 minutes ago
 *     !ride.isStart && ride.getEuclideanDistance(-74, 41) < 10.0 -- match rides that end within 10km of the given location
 */

public class TaxiQueryExercise extends ExerciseBase {

	final static String QUERY_KEY = "query";
	final static MapStateDescriptor queryDescriptor = new MapStateDescriptor<>(
			"queries",
			BasicTypeInfo.STRING_TYPE_INFO,
			TypeInformation.of(ExpressionEvaluator.class)
	);

	public static void main(String[] args) throws Exception {

		ParameterTool params = ParameterTool.fromArgs(args);
		final String input = params.get("input", ExerciseBase.pathToRideData);

		final int maxEventDelay = 60;       	// events are out of order by at most 60 seconds
		final int servingSpeedFactor = 1800; 	// 30 minutes worth of events are served every second

		// set up streaming execution environment
		StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();
		env.setStreamTimeCharacteristic(TimeCharacteristic.EventTime);
		env.setParallelism(ExerciseBase.parallelism);

		// setup a stream of taxi rides
		DataStream<TaxiRide> rides = env.addSource(rideSourceOrTest(new TaxiRideSource(input, maxEventDelay, servingSpeedFactor)));

		// add a socket source for the query stream
		BroadcastStream<String> queryStream = env
				.addSource(stringSourceOrTest(new SocketTextStreamFunction("localhost", 9999, "\n", -1)))
				.assignTimestampsAndWatermarks(new AssignerWithPeriodicWatermarks<String>() {
					@Nullable
					@Override
					public Watermark getCurrentWatermark() {
						return Watermark.MAX_WATERMARK;
					}

					@Override
					public long extractTimestamp(String element, long previousElementTimestamp) {
						return 0;
					}
				})
				.broadcast(queryDescriptor);

		// connect the two streams and process queries
		DataStream<Tuple2<String, String>> results = rides
				.keyBy((TaxiRide ride) -> ride.taxiId)
				.connect(queryStream)
				.process(new QueryProcessor());

		printOrTest(results);

		env.execute("Taxi Query");
	}

	public static class QueryProcessor extends KeyedBroadcastProcessFunction<Long, TaxiRide, String, Tuple2<String, String>> {
		private ValueStateDescriptor<TaxiRide> rideDescriptor =
				new ValueStateDescriptor<>("saved ride", TaxiRide.class);
		private ValueState<TaxiRide> taxiState;

		private static transient DateTimeFormatter timeFormatter =
				DateTimeFormat.forPattern("yyyy-MM-dd HH:mm:ss").withLocale(Locale.US).withZoneUTC();

		@Override
		public void open(Configuration config) {
			taxiState = getRuntimeContext().getState(rideDescriptor);
		}

		@Override
		public void processElement(TaxiRide ride, ReadOnlyContext ctx, Collector<Tuple2<String, String>> out) throws Exception {

			// for every taxi, we want to store the most up-to-date information
			TaxiRide savedRide = taxiState.value();
			if (ride.compareTo(savedRide) > 0) {
				taxiState.update(ride);
			}

			// only collect this event if the current query evaluates to TRUE for this ride
			// processElement() is to process new coming ride info with existed query in broadcast state
			if (ctx.getBroadcastState(queryDescriptor).immutableEntries().iterator().hasNext()) {
				ExpressionEvaluator ee = ((Map.Entry<String, ExpressionEvaluator>) ctx.getBroadcastState(queryDescriptor).immutableEntries().iterator().next()).getValue();
				if (evaluateBooleanExpression(ee, ride, ctx.currentWatermark())) {
					out.collect(new Tuple2<>("PE@" + timeFormatter.print(ctx.currentWatermark()), ride.toString()));
				}
			}
		}

		/**
		 * processBroadcastElement() is to process new coming query from socket, which is to apply this
		 * query with each stored taxi's most up-to-date info
		 * @param query
		 * @param ctx
		 * @param out
		 * @throws Exception
		 */
		@Override
		public void processBroadcastElement(String query,
											Context ctx,
											Collector<Tuple2<String, String>> out) throws Exception {

			out.collect(new Tuple2<>("QUERY", query));

			// Store the incoming query in broadcast state
			ctx.getBroadcastState(queryDescriptor).put(QUERY_KEY, cookBooleanExpression(query));

			// Use applyToKeyedState to process the incoming query with every saved ride
			ctx.applyToKeyedState(queryDescriptor, new KeyedStateFunction<Long, State>() {
				@Override
				public void process(Long aLong, State state) throws Exception {
					TaxiRide ride = taxiState.value();
					// Whenever the query evaluates to TRUE, emit that saved ride
					if (evaluateBooleanExpression((ExpressionEvaluator) ctx.getBroadcastState(queryDescriptor).get(QUERY_KEY), ride, ctx.currentWatermark())) {
						out.collect(new Tuple2<>("PBE@" + timeFormatter.print(ctx.currentWatermark()), ride.toString()));
					}
				}
			});
		}

		private ExpressionEvaluator cookBooleanExpression(String expression) throws CompileException {
			ExpressionEvaluator ee = new ExpressionEvaluator();
			ee.setParameters(new String[] { "ride", "watermark" }, new Class[] { TaxiRide.class, long.class });
			ee.setExpressionType(boolean.class);
			ee.cook(expression);

			return ee;
		}

		private boolean evaluateBooleanExpression(ExpressionEvaluator ee, TaxiRide ride, long watermark) throws InvocationTargetException {
			boolean result= false;
			if (ee != null) {
				result = (boolean) ee.evaluate(new Object[] { ride, watermark });
			}
			return result;
		}
	}
}