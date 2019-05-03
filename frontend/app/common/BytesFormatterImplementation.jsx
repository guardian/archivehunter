class BytesFormatterImplementation {
    static suffixes = ["bytes", "Kb", "Mb", "Gb", "Tb"];

    static reduceValue(startAt){
        let current = startAt;
        let c=0;

        while(current>1024 && c < BytesFormatterImplementation.suffixes.length-1){
            ++c;
            current = current/1024;
        }
        return [current, BytesFormatterImplementation.suffixes[c]]
    }

    static getValueAndSuffix(startingValue){
        const result = BytesFormatterImplementation.reduceValue(startingValue);
        //parseFloat is necessary to remove the scientific notation
        const numeric = result[0] < 1024 ? parseFloat(result[0].toPrecision(3)) : result[0];
        return [numeric, result[1]];
    }
}

export default BytesFormatterImplementation;