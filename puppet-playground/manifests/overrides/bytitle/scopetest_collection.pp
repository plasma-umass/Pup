notify {'toplevel notify':}

class someclass {

# This fails as the toplevel notify is not in its scope
  Notify <| title == 'toplevel notify' |> {
    message => 'overridden message'
  }

  notify{'class notify':}
}

include someclass
