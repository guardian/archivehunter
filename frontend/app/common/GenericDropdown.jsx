import React from 'react';
import PropTypes from 'prop-types';

class GenericDropdown extends React.Component {
    static propTypes = {
        valueList: PropTypes.array.isRequired,
        onChange: PropTypes.func.isRequired,
        value: PropTypes.string.isRequired
    };

    constructor(props){
        super(props);
    }

    render(){
        return <select onChange={this.props.onChange} value={this.props.value}>
            {
                this.props.valueList.map(entry=><option key={entry} value={entry}>{entry}</option>)
            }
        </select>
    }
}

export default GenericDropdown;