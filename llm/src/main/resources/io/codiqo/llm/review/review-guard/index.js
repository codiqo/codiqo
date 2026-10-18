// The OpenCode plugin every local review runs with (ReviewGuard.install). It does two things the models proved unable
// to do for themselves, and OpenCode 2.0.32 offers neither:
//
// - every tool result ends with "[step N of M]", the step the call was made in, read from OpenCode's own messages: the
//   prompts asked the agents to count their steps, and in 31 reviewer sessions only 13 did, the coordinator never;
// - a third identical tool call in one session is refused, with an answer the model reads: a coordinator ran one grep
//   95 times with the same reasoning until its step cap. OpenCode's doom_loop guard only catches repeated failures.
//
// A step is one model call, and every tool call of one call shares its message, so the distinct messages that called a
// tool are the steps taken. A sub-agent runs in a session of its own and is counted on its own.

const REPEAT_LIMIT = 2;

export default {
  id: "codiqo-review-guard",
  setup(ctx) {
    const steps = ctx.options.steps;
    const sessions = new Map();
    const session = (id) => {
      let toReturn = sessions.get(id);
      if (toReturn === undefined) {
        toReturn = { messages: new Set(), calls: new Map() };
        sessions.set(id, toReturn);
      }
      return toReturn;
    };

    // A refused call is a step too, so its error carries the note: a hook that throws gets no execute.after. A call
    // the permissions deny keeps OpenCode's own error, which a plugin cannot change, and goes without one.
    const stepNote = (call) => {
      const max = steps[call.agent];
      return max === undefined ? undefined : `[step ${session(call.sessionID).messages.size} of ${max}]`;
    };

    ctx.tool.hook("execute.before", (call) => {
      const state = session(call.sessionID);
      state.messages.add(call.messageID);
      const key = call.tool + " " + JSON.stringify(call.input);
      const runs = (state.calls.get(key) ?? 0) + 1;
      state.calls.set(key, runs);
      if (runs > REPEAT_LIMIT) {
        const refusal =
          `Refused: this exact ${call.tool} call already ran ${REPEAT_LIMIT} times in this session, and its result cannot change. ` +
          "Use the result you already have, or make a different call.";
        const note = stepNote(call);
        throw new Error(note === undefined ? refusal : `${refusal}\n\n${note}`);
      }
    });

    ctx.tool.hook("execute.after", (call) => {
      const note = stepNote(call);
      if (call.status === "completed" && note !== undefined) {
        const content = call.result.content;
        let noted;
        if (typeof content === "string") noted = `${content}\n\n${note}`;
        else if (Array.isArray(content)) noted = [...content, { type: "text", text: note }];
        else noted = note;
        call.result = { ...call.result, content: noted };
      }
    });
  },
};
