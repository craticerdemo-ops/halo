'use strict';

// `node --test <directory>` resolves the directory as a module on current Node
// releases, so this entry point registers every suite in the directory.
require('./detect.test.js');
require('./extract.test.js');
require('./comments.test.js');
require('./payload.test.js');
require('./submit.test.js');
require('./fixtures.test.js');
