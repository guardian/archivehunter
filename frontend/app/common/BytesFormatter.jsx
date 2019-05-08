import React from 'react';
import PropTypes from 'prop-types';
import BytesFormatterImplementation from './BytesFormatterImplementation.jsx';


class BytesFormatter extends React.Component {
    static propTypes = {
        value: PropTypes.number.isRequired
    };

    render(){
        if(!this.props.value) return <span/>;
        const result = BytesFormatterImplementation.getValueAndSuffix(this.props.value);
        return <span>{result[0]} {result[1]}</span>
    }
}

export default BytesFormatter;