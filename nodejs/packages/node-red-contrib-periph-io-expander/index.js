const fs = require('fs');
const path = require('path');

module.exports = function(RED) {
    const nodesDir = path.join(__dirname, 'nodes');
    if (!fs.existsSync(nodesDir)) return;
    fs.readdirSync(nodesDir).forEach(function(chip) {
        const nodeFile = path.join(nodesDir, chip, chip + '.js');
        if (fs.existsSync(nodeFile)) require(nodeFile)(RED);
    });
};
