import React from 'react';
import { FontAwesomeIcon } from '@fortawesome/react-fontawesome'
import PropTypes from 'prop-types';

class RefreshButton extends React.Component {
    static propTypes = {
        isRunning: PropTypes.bool.isRequired,
        clickedCb: PropTypes.func.isRequired
    };

    constructor(props){
        super(props);
        this.state = {
            spinAngle: 0
        }
    }

    render() {
        return <FontAwesomeIcon icon="redo-alt"
                                className={this.props.isRunning ? "button-icon spin" : "button-icon"}
                                onClick={this.props.clickedCb}/>
    }
}

export default RefreshButton;