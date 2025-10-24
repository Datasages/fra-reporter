// Debug script to check 2083 message destination addresses
// Run with: mongo amtk_reports debug-enforcement.js

print("Checking 2083 messages in amtk_messages collection...");

// Count total 2083 messages
var total2083 = db.amtk_messages.countDocuments({idType: 2083});
print("Total 2083 messages: " + total2083);

// Get unique destination addresses for 2083 messages
print("\nUnique destination addresses for 2083 messages:");
var destAddresses = db.amtk_messages.distinct("destAddress", {idType: 2083});
destAddresses.forEach(function(addr) {
    var count = db.amtk_messages.countDocuments({idType: 2083, destAddress: addr});
    print("  " + addr + ": " + count + " messages");
});

// Check specifically for the addresses the report is looking for
print("\nChecking for specific addresses the Enforcement Report looks for:");
var necCount = db.amtk_messages.countDocuments({idType: 2083, destAddress: "amtk.b:gb.nec"});
var meCount = db.amtk_messages.countDocuments({idType: 2083, destAddress: "amtk.b:gb.me"});
print("  amtk.b:gb.nec: " + necCount + " messages");
print("  amtk.b:gb.me: " + meCount + " messages");

// Get recent messages to see the structure
print("\nSample 2083 message structure:");
var sample = db.amtk_messages.findOne({idType: 2083});
if (sample) {
    print("  destAddress: " + sample.destAddress);
    print("  srcAddress: " + sample.srcAddress);
    print("  time: " + sample.time);
    if (sample.enforcementScac) print("  enforcementScac: " + sample.enforcementScac);
    if (sample.emergencyEnforcementScac) print("  emergencyEnforcementScac: " + sample.emergencyEnforcementScac);
    if (sample.targetType) print("  targetType: " + sample.targetType);
}