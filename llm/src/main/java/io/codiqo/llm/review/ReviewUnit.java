package io.codiqo.llm.review;

import lombok.Value;

/** One code unit of the commit as the labelling turn lists it: the analysis's own name and lines, under an id to answer by. */
@Value
public class ReviewUnit {
    String id;
    String path;
    String name;
    String operation;
    String lines;
}
