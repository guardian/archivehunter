import React from 'react';
import PropTypes from 'prop-types';

class BytesFormatter extends React.Component {
    static propTypes = {
        value: PropTypes.number.isRequired
    };
    static suffixes = ["bytes", "Kb", "Mb", "Gb", "Tb"];

    reduceValue(){
        let current = this.props.value;
        let c=0;

        while(current>1024 && c < BytesFormatter.suffixes.length-1){
            ++c;
            current = current/1024;
        }
        return [current, BytesFormatter.suffixes[c]]
    }

    render(){
        const result = this.reduceValue();

        return <span>{result[0].toPrecision(3)} {result[1]}</span>
    }
}

export default BytesFormatter;