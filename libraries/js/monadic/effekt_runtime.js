const $runtime = (function() {

  // Naming convention:
  // k ^= meta-continuation
  // c ^= computation (wihtin the Control Monad)
  // p ^= prompt
  // a ^= value
  // f ^= frame

  // Result -- Trampoline
  // (c: Control[A], k: Stack) -> Step
  function Step(c, k) {
    return { isStep: true, c: c, k: k }
  }

  // (r: Step) -> A
  function trampoline(r) {
    var res = r
    while (res !== null && res !== undefined && res.isStep) {
      res = res.c.apply(res.k)
    }
    return res
  }

  // Lists / Pairs. Only used immutably!
  // (head: A, tail: A) -> Cons[A]
  function Cons(head, tail) {
    return { head: head, tail: tail }
  }

  const Nil = null

  // Frame = A => Control[B]

  // Metacontinuations / Stacks
  // A metacontinuation is a stack of stacks.
  // (frames: List[Frame], fields: [Cell], prompt: Int, clauses: Clauses, tail: Stack) -> Stack
  function Stack(frames, fields, prompt, clauses, tail) {
    return { frames: frames, fields: fields, prompt: prompt, clauses: clauses, tail: tail }
  }

  // (frames: List[Frame], fields: [Cell], prompt: Int, clauses: Clauses, tail: Stack) -> Stack
  function SubStack(frames, backup, prompt, clauses, onUnwindData, tail) {
    return { frames: frames, backup: backup, prompt: prompt, clauses: clauses, onUnwindData: onUnwindData, tail: tail }
  }

  const EmptyStack = null;

  // |       |
  // | - T - |
  // |-------| - R
  //
  // onReturn: T -> Control[R]
  // onUnwind: () -> Control[S]
  // onRewind: S -> Control[Unit]
  function Clauses(onUnwind = undefined, onRewind = undefined, onReturn = undefined) {
    return {
      onUnwind: onUnwind, onRewind: onRewind, onReturn: onReturn
    }
  }

  // return a to stack
  // (stack: Stack<A, B>, a: A) -> Step<B>
  function apply(stack, a) {
    var s = stack;
    while (true) {
      if (s === EmptyStack) return a;
      const fs = s.frames;
      if (fs === Nil) {
        if (s.clauses.onReturn) {
          return Step(s.clauses.onReturn(a), s.tail)
        } else {
          s = s.tail;
          continue
        }
      }
      const result = fs.head(a);
      s.frames = fs.tail;
      return Step(result, s)
    }
  }

  // A cell is a mutable variable with an intial state.
  // (init: A) -> Cell[A]
  function Cell(init) {
    var _value = init;
    return {
      "op$get": function() {
        return $effekt.pure(_value)
      },
      "op$put": function(v) {
        _value = v;
        return $effekt.pure($effekt.unit)
      },
      backup: function() {
        var _backup = _value
        var cell = this;
        return () => { _value = _backup; return cell }
      }
    }
  }

  // (cells: [Cell]) -> [() => Cell]
  function backup(cells) {
    return cells.map(c => c.backup())
  }

  // (b: [() => Cell]) -> [Cell]
  function restore(b) {
    return b.map(c => c())
  }

  // Corresponds to a stack rewind.
  // (subcont: Stack, stack: Stack, c: Control[A]) -> Step
  function pushSubcont(subcont, stack, c) {
    var sub = subcont;
    var s = stack;

    while (sub !== EmptyStack) {
      // push sub onto s while restoring sub's cells
      s = Stack(sub.frames, restore(sub.backup), sub.prompt, sub.clauses, s)
      if (sub.clauses.onRewind !== null) {
        // push sub onto s and execute the on rewind clause on the resulting stack.
        // Then remember to push sub.tail on the resulting stack k.
        return Step(
          sub.clauses.onRewind(sub.onUnwindData)
            .then(() => Control(k => pushSubcont(sub.tail, k, c))),
          s
        )
      }
      sub = sub.tail
    }
    return Step(c, s);
  }

  function pushSubcont_(subcont, stack, c) {
    if (subcont === EmptyStack) return Step(c, stack)
    else {
      const s = Stack(subcont.frames, restore(subcont.backup), subcont.prompt, subcont.clauses, stack)
      if (subcont.clauses.onRewind !== null) {
        return Step(
          subcont.clauses.onRewind(subcont.onUnwindData)
            .then(() => Control(k => pushSubcont_(subcont.tail, k, c))),
          s
        )
      }
      return pushSubcont_(
        subcont.tail,
        s,
        c
      )
    }
  }

  // Pushes a frame f onto the stack
  // (stack: Stack, f: Frame) -> Stack
  function flatMap(stack, f) {
    if (stack === EmptyStack) { return Stack(Cons(f, Nil), [], null, stack) }
    var fs = stack.frames
    // it should be safe to mutate the frames field, since they are copied in the subcont
    stack.frames = Cons(f, fs)
    return stack
  }

  // Corresponds to a stack unwind. Pops off stacks until the stack with prompt has been found
  // (stack: Stack, p: Int) -> Tuple[Stack, Stack]
  function splitAt(stack, p) {
    var sub = EmptyStack;
    var s = stack;

    while (s !== EmptyStack) {
      const currentPrompt = s.prompt
      var onUnwindData = null
      const onUnwindOp = s.clauses.onUnwind
      if (onUnwindOp != null) {
        onUnwindData = onUnwindOp().run()
      }
      sub = SubStack(s.frames, backup(s.fields), currentPrompt, s.clauses, onUnwindData, sub)
      s = s.tail
      if (currentPrompt === p) { return Cons(sub, s) }
    }
    throw ("Prompt " + p + " not found")
  }

  // (stack: Stack, sub: Stack, p: Int, f: Frame) -> Step
  function splitAt_(stack, sub, p, f) {
    if (stack === EmptyStack) {
      throw ("Prompt " + p + " not found")
    }
    const currentPrompt = stack.prompt
    const resume = sub_ => {
      const localCont = a => Control(k => {
        return pushSubcont(sub_, k, pure(a))
      })
      return Step(f(localCont), stack.tail)
    }
    const onUnwindOp = stack.clauses.onUnwind
    // if the current stack has a on unwind / on suspend operation, run it and then
    // either keep on searching for the correct prompt using the given continuation or resume.
    if (onUnwindOp != null) {
      return Step(
        onUnwindOp().then(a => Control(k => {
          const sub_ = SubStack(stack.frames, backup(stack.fields), currentPrompt, stack.clauses, a, sub)
          if (currentPrompt === p) {
            return resume(sub_)
          }
          return splitAt_(k, sub_, p, f)
        })),
        stack.tail
      )
    }

    const sub_ = SubStack(stack.frames, backup(stack.fields), currentPrompt, stack.clauses, null, sub)
    if (currentPrompt === p) {
      // return Cons(sub_, stack.tail)
      return resume(sub_)
    }
    return splitAt_(stack.tail, sub_, p, f)
  }

  // (init: A, f: Frame) -> Control[B]
  function withState(init, f) {
    const cell = Cell(init)
    return Control(k => {
      k.fields.push(cell);
      return Step(f(cell), k)
    })
  }

  // Delimited Control "monad"
  function Control(apply) {
    const self = {
      // Control[A] -> Stack -> Step[Control[B], Stack]
      apply: apply,
      // Control[A] -> A
      run: () => trampoline(Step(self, Stack(Nil, [], toplevel, Clauses(), EmptyStack))),
      // Control[A] -> (A => Control[B]) -> Control[B] 
      // which corresponds to monadic bind
      then: f => Control(k => Step(self, flatMap(k, f))),
      // Control[A] -> (A => Control[B]) -> Control[B]
      state: f => self.then(init => withState(init, f))
    }
    return self
  }

  // Given a continuation, return/apply a to it
  // A => Control[A]
  const pure = a => Control(k => apply(k, a))

  // Delays native JS side-effects during creation of the Control Monad.
  const delayed = a => Control(k => apply(k, a()))

  const shift = p => f => Control(k => {
    //TODO A name change of splitAt is probably in order
    return splitAt_(k, null, p, f)
    // localCont corresponds to resume(a)
    // const localCont = a => Control(k => {
    //   return pushSubcont(split.head, k, pure(a))
    // })
    // return Step(f(localCont), split.tail)
  })

  const callcc = f => Control(k => {
    return f(a => trampoline(apply(k, a)))
  })

  const abort = Control(k => $effekt.unit)

  const capture = f => {
    // [abort; f
    const action = () => f($effekt.unit).then(() => abort)
    return shift(toplevel)(k =>
      k({
        shouldRun: false,
        cont : () => k({ shouldRun: true, cont: action })
      })).then(a => a.shouldRun ? a.cont() : $effekt.pure(a.cont))
  }

  const reset = p => clauses => c => Control(k => Step(c, Stack(Nil, [], p, clauses, k)))

  const toplevel = 1;
  // A unique id for each handle.
  var _prompt = 2;

  function _while(c, body) {
    return c().then(b => b ? body().then(() => _while(c, body)) : pure($effekt.unit))
  }

  function handle(handlers, onUnwind = null, onRewind = null, onReturn = null) {
    const p = _prompt++;

    // modify all implementations in the handlers to capture the continuation at prompt p
    const caps = handlers.map(h => {
      var cap = Object.create({})
      for (var op in h) {
        const impl = h[op];
        cap[op] = function() {
          // split two kinds of arguments, parameters of the operation and capabilities
          const args = Array.from(arguments);
          const arity = impl.length - 1
          const oargs = args.slice(0, arity)
          const caps = args.slice(arity)
          var r = shift(p)(k => impl.apply(null, oargs.concat([k])))
          // resume { caps => e}
          if (caps.length > 0) {
            return r.then(f => f.apply(null, caps))
          }
          // resume(v)
          else {
            return r
          }
        }
      }
      return cap;
    });
    return body => reset(p)(Clauses(onUnwind, onRewind, onReturn))(body.apply(null, caps))
  }

  return {
    pure: pure,
    callcc: callcc,
    capture: capture,
    delayed: delayed,
    // no lifting for prompt based implementation
    lift: f => f,
    handle: handle,

    _if: (c, thn, els) => c ? thn() : els(),
    _while: _while,
    constructor: (_, tag) => function() {
      return { __tag: tag, __data: Array.from(arguments) }
    },

    hole: function() { throw "Implementation missing" }
  }
})()

var $effekt = {}

Object.assign($effekt, $runtime);

module.exports = $effekt