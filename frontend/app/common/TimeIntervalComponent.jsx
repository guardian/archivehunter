import React from 'react';
import PropTypes from 'prop-types';
import moment from 'moment';
import 'moment-duration-format';

class TimeIntervalComponent extends React.Component {
    static propTypes = {
        editable: PropTypes.bool.isRequired,
        value: PropTypes.number.isRequired
    };

    render(){
        const d = moment.duration(this.props.value,"seconds");
        return <span className="duration">{d.format()}</span>
    }
}

export default TimeIntervalComponent;