/*
 * Copyright 2017 data Artisans GmbH
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

package com.dataartisans.flinktraining.exercises.datastream_java.process;

import com.dataartisans.flinktraining.exercises.datastream_java.datatypes.TaxiFare;
import com.dataartisans.flinktraining.exercises.datastream_java.datatypes.TaxiRide;
import com.dataartisans.flinktraining.exercises.datastream_java.sources.CheckpointedTaxiFareSource;
import com.dataartisans.flinktraining.exercises.datastream_java.sources.CheckpointedTaxiRideSource;
import com.dataartisans.flinktraining.exercises.datastream_java.utils.ExerciseBase;
import com.dataartisans.flinktraining.exercises.datastream_java.utils.MissingSolutionException;
import org.apache.flink.api.common.state.ValueState;
import org.apache.flink.api.common.state.ValueStateDescriptor;
import org.apache.flink.api.common.typeinfo.TypeInformation;
import org.apache.flink.api.java.tuple.Tuple2;
import org.apache.flink.api.java.utils.ParameterTool;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.streaming.api.TimeCharacteristic;
import org.apache.flink.streaming.api.datastream.DataStream;
import org.apache.flink.streaming.api.datastream.SingleOutputStreamOperator;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.streaming.api.functions.co.CoProcessFunction;
import org.apache.flink.util.Collector;
import org.apache.flink.util.OutputTag;

/**
 * The "Expiring State" exercise from the Flink training
 * (http://training.data-artisans.com).
 *
 * The goal for this exercise is to enrich TaxiRides with fare information.
 *
 * Parameters:
 * -rides path-to-input-file
 * -fares path-to-input-file
 *
 */
public class ExpiringStateExercise extends ExerciseBase {
	static final OutputTag<TaxiRide> unmatchedRides = new OutputTag<TaxiRide>("unmatchedRides") {};
	static final OutputTag<TaxiFare> unmatchedFares = new OutputTag<TaxiFare>("unmatchedFares") {};

	public static void main(String[] args) throws Exception {

		ParameterTool params = ParameterTool.fromArgs(args);
		final String ridesFile = params.get("rides", ExerciseBase.pathToRideData);
		final String faresFile = params.get("fares", ExerciseBase.pathToFareData);

		final int servingSpeedFactor = 600; 	// 10 minutes worth of events are served every second

		// set up streaming execution environment
		StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();
		env.setStreamTimeCharacteristic(TimeCharacteristic.EventTime);
		env.setParallelism(ExerciseBase.parallelism);

		DataStream<TaxiRide> rides = env
				.addSource(rideSourceOrTest(new CheckpointedTaxiRideSource(ridesFile, servingSpeedFactor)))
				.filter((TaxiRide ride) -> (ride.isStart && (ride.rideId % 1000 != 0)))
				.keyBy(ride -> ride.rideId);

		DataStream<TaxiFare> fares = env
				.addSource(fareSourceOrTest(new CheckpointedTaxiFareSource(faresFile, servingSpeedFactor)))
				.keyBy(fare -> fare.rideId);

		SingleOutputStreamOperator processed = rides
				.connect(fares)
				.process(new EnrichmentFunction());

		printOrTest(processed.getSideOutput(unmatchedFares));

		env.execute("ExpiringStateExercise (java)");
	}

	public static class EnrichmentFunction extends CoProcessFunction<TaxiRide, TaxiFare, Tuple2<TaxiRide, TaxiFare>> {

		private ValueState<TaxiRide> rideValueState;
		private ValueState<TaxiFare> fareValueState;

		@Override
		public void open(Configuration config) throws Exception {
			// initialize state
			rideValueState = getRuntimeContext()
					.getState(new ValueStateDescriptor<TaxiRide>("ride state", TypeInformation.of(TaxiRide.class)));

			fareValueState = getRuntimeContext()
					.getState(new ValueStateDescriptor<TaxiFare>("fare state", TypeInformation.of(TaxiFare.class)));

		}

		@Override
		public void onTimer(long timestamp, OnTimerContext ctx, Collector<Tuple2<TaxiRide, TaxiFare>> out) throws Exception {
			if (fareValueState.value() != null) {
				ctx.output(unmatchedFares, fareValueState.value());
				fareValueState.clear();
			}
			if (rideValueState.value() != null) {
				ctx.output(unmatchedRides, rideValueState.value());
				fareValueState.clear();
			}
		}

		@Override
		public void processElement1(TaxiRide ride, Context context, Collector<Tuple2<TaxiRide, TaxiFare>> out) throws Exception {
			TaxiFare statedFare = fareValueState.value();
			if (statedFare == null) {
				rideValueState.update(ride);
				context.timerService().registerEventTimeTimer(ride.getEventTime());  // if watermark reaches current ride event's timestamp, then missing
				                                                                     // fare will no longer be waited and onTimer() will be invoked
			} else {
				fareValueState.clear();
				out.collect(new Tuple2<>(ride, statedFare));
			}
		}

		@Override
		public void processElement2(TaxiFare fare, Context context, Collector<Tuple2<TaxiRide, TaxiFare>> out) throws Exception {
			TaxiRide statedRide = rideValueState.value();
			if (statedRide == null) {
				fareValueState.update(fare);
				context.timerService().registerEventTimeTimer(fare.getEventTime());
			} else {
				rideValueState.clear();
				out.collect(new Tuple2<>(statedRide, fare));
			}
		}
	}
}