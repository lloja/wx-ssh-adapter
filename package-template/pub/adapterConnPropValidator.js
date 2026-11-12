function sshAdapterConnPropValidator(theForm) {
    var portStr = (theForm.elements['.CPROP.port'] != undefined) ? theForm.elements['.CPROP.port'].value : "";
    if (portStr.length > 0 && (!isNumber(portStr) || parseInt(portStr) <= 0 || parseInt(portStr) > 65535)) {
        return "Invalid Port Number: must be between 1 and 65535.";
    }
    return "";
}
adapterConnPropValidator = sshAdapterConnPropValidator;
