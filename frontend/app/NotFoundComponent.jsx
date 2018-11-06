import React from 'react';
import PropTypes from 'prop-types';
import { FontAwesomeIcon } from '@fortawesome/react-fontawesome'

class NotFoundComponent extends React.Component {
    render(){
        return <h1><FontAwesomeIcon style={{color: "orange"}} icon="stroopwafel"/>Not found</h1>;
    }
}

export default NotFoundComponent;