# CopybookSlurper
Creates maps of data representing a COBOL copybook. Behind the scenes it uses the [IBM Jzos library](https://www-01.ibm.com/marketing/iwm/iwm/web/reg/download.do?source=jzosetzosjsdk&S_PKG=dl&lang=en_US&cp=UTF-8&dlmethod=http) to write/parse bytes into Java objects. The slurper should be created once since it does a lot of heavy lifting by parsing through the copybook definition. The getCopybook and callCics methods can be used repeatedly.

```sh
 def slurper = new CopybookSlurper("""
 01 EXAMPLE.
    03  STATES OCCURS 2 TIMES.
        04 STATE_NAME    PIC X(10).
 """)
 def reader = slurper.getCopybook(byteStream)
 reader.STATES[1].STATE_NAME
```

```sh
 def slurper = new CopybookSlurper('''\
 01 TESTING-COPYBOOK.
    03  TEST-NAME                         PIC X(10).
    03  STATES OCCURS 2 TIMES .
        05  STATE-ABBR                    PIC XX.
        05  STATE-NAMES OCCURS 2 TIMES.
            08  STATE-NUM                 PIC 99.
            08  STATE-NAME                PIC X(10).
        05  STATE-RANKING                 PIC 9.
 03  theEnd                               PIC XXX.''')
 def reader = slurper.parse('STATETEST MI01MICHIGAN  02MICH      1OH01OHIO      02          2END'.getBytes('IBM-37'))
 
 println results.STATES[1].STATE_ABBR
 //output: 'OH'
 
 results.STATES[0].each {
   println it.STATE_NUM + '-' + it.STATE_NAME
 }
 //output: 1-MICHIGAN
           2-OHIO
 println results.toString()
 //output, normally a single line
 [STATES:[[STATE_RANKING:1, STATE_NAMES:[[STATE_NAME:MICHIGAN, STATE_NUM:1],
                                         [STATE_NAME:MICH, STATE_NUM:2]], STATE_ABBR:MI],
          [STATE_RANKING:2, STATE_NAMES:[[STATE_NAME:OHIO, STATE_NUM:1],
                                         [STATE_NAME:, STATE_NUM:2]], STATE_ABBR:OH]],
 *  TEST_NAME:STATETEST, theEnd:YES]
```

There is also a feature to call CICS which uses classes from the [IBM CTG gateway](http://www-03.ibm.com/software/products/en/cics-ctg). 
```sh
 def slurper = new CopybookSlurper('''\
 01 GET-INFO.
    03  NAME                         PIC X(10).
    03  DEPT                         PIC X(10).

def userInfo = slurper.callCics('MODULE') { Copybook input ->
  input.'NAME' = 'JOHN SMITH'
}
println userInfo.'DEPT'
```

Both the JZOS and CTG libraries are licensed by IBM, so they are not available in a public repository like Maven, which is why I haven't included a build script.
